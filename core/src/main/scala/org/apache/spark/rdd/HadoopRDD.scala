/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.rdd

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.{Date, Locale}

import scala.collection.immutable.Map
import scala.reflect.ClassTag

import org.apache.hadoop.conf.{Configurable, Configuration}
import org.apache.hadoop.mapred.FileSplit
import org.apache.hadoop.mapred.InputFormat
import org.apache.hadoop.mapred.InputSplit
import org.apache.hadoop.mapred.JobConf
import org.apache.hadoop.mapred.JobID
import org.apache.hadoop.mapred.RecordReader
import org.apache.hadoop.mapred.Reporter
import org.apache.hadoop.mapred.TaskAttemptID
import org.apache.hadoop.mapred.TaskID
import org.apache.hadoop.mapred.lib.CombineFileSplit
import org.apache.hadoop.mapreduce.TaskType
import org.apache.hadoop.util.ReflectionUtils

import org.apache.spark._
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config.IGNORE_CORRUPT_FILES
import org.apache.spark.rdd.HadoopRDD.HadoopMapPartitionsWithSplitRDD
import org.apache.spark.scheduler.{HDFSCacheTaskLocation, HostTaskLocation}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.util.{NextIterator, SerializableConfiguration, ShutdownHookManager, Utils}

/**
 * A Spark split class that wraps around a Hadoop InputSplit.
 */
private[spark] class HadoopPartition(rddId: Int, override val index: Int, s: InputSplit)
  extends Partition {

  // 将数据分片构造为可序列化的对象
  val inputSplit = new SerializableWritable[InputSplit](s)

  override def hashCode(): Int = 31 * (31 + rddId) + index

  // 只有两个HadoopPartition的引用相同才算相同
  override def equals(other: Any): Boolean = super.equals(other)

  /**
   * Get any environment variables that should be added to the users environment when running pipes
    *
    * 获取需要添加到用户运行环境的环境变量
    *
   * @return a Map with the environment variables and corresponding values, it could be empty
   */
  def getPipeEnvVars(): Map[String, String] = {
    val envVars: Map[String, String] =
      // 判断数据分片是否是FileSplit分片
      if (inputSplit.value.isInstanceOf[FileSplit]) {
          // 转换数据分片为FileSplit
          val is: FileSplit = inputSplit.value.asInstanceOf[FileSplit]
          // map_input_file is deprecated in favor of mapreduce_map_input_file but set both
          // since it's not removed yet
          // 将文件分片的路径保存为map_input_file和mapreduce_map_input_file属性
          Map("map_input_file" -> is.getPath().toString(),
          "mapreduce_map_input_file" -> is.getPath().toString())
      } else {
        Map()
      }
    // 返回环境变量
    envVars
  }
}

/**
 * :: DeveloperApi ::
 * An RDD that provides core functionality for reading data stored in Hadoop (e.g., files in HDFS,
 * sources in HBase, or S3), using the older MapReduce API (`org.apache.hadoop.mapred`).
 *
 * @param sc The SparkContext to associate the RDD with.
 * @param broadcastedConf A general Hadoop Configuration, or a subclass of it. If the enclosed
 *   variable references an instance of JobConf, then that JobConf will be used for the Hadoop job.
 *   Otherwise, a new JobConf will be created on each slave using the enclosed Configuration.
 * @param initLocalJobConfFuncOpt Optional closure used to initialize any JobConf that HadoopRDD
 *     creates.
 * @param inputFormatClass Storage format of the data to be read.
 * @param keyClass Class of the key associated with the inputFormatClass.
 * @param valueClass Class of the value associated with the inputFormatClass.
 * @param minPartitions Minimum number of HadoopRDD partitions (Hadoop Splits) to generate.
 *
 * @note Instantiating this class directly is not recommended, please use
 * `org.apache.spark.SparkContext.hadoopRDD()`
 */
@DeveloperApi
class HadoopRDD[K, V](
    sc: SparkContext,
    broadcastedConf: Broadcast[SerializableConfiguration],
    initLocalJobConfFuncOpt: Option[JobConf => Unit],
    inputFormatClass: Class[_ <: InputFormat[K, V]],
    keyClass: Class[K],
    valueClass: Class[V],
    minPartitions: Int)
  extends RDD[(K, V)](sc, Nil) with Logging {

  if (initLocalJobConfFuncOpt.isDefined) {
    sparkContext.clean(initLocalJobConfFuncOpt.get)
  }

  def this(
      sc: SparkContext,
      conf: JobConf,
      inputFormatClass: Class[_ <: InputFormat[K, V]],
      keyClass: Class[K],
      valueClass: Class[V],
      minPartitions: Int) = {
    this(
      sc,
      sc.broadcast(new SerializableConfiguration(conf))
        .asInstanceOf[Broadcast[SerializableConfiguration]],
      initLocalJobConfFuncOpt = None,
      inputFormatClass,
      keyClass,
      valueClass,
      minPartitions)
  }

  protected val jobConfCacheKey = "rdd_%d_job_conf".format(id)

  protected val inputFormatCacheKey = "rdd_%d_input_format".format(id)

  // used to build JobTracker ID
  private val createTime = new Date()

  private val shouldCloneJobConf = sparkContext.conf.getBoolean("spark.hadoop.cloneConf", false)

  private val ignoreCorruptFiles = sparkContext.conf.get(IGNORE_CORRUPT_FILES)

  // Returns a JobConf that will be used on slaves to obtain input splits for Hadoop reads.
  protected def getJobConf(): JobConf = {
    val conf: Configuration = broadcastedConf.value.value
    if (shouldCloneJobConf) {
      // Hadoop Configuration objects are not thread-safe, which may lead to various problems if
      // one job modifies a configuration while another reads it (SPARK-2546).  This problem occurs
      // somewhat rarely because most jobs treat the configuration as though it's immutable.  One
      // solution, implemented here, is to clone the Configuration object.  Unfortunately, this
      // clone can be very expensive.  To avoid unexpected performance regressions for workloads and
      // Hadoop versions that do not suffer from these thread-safety issues, this cloning is
      // disabled by default.
      HadoopRDD.CONFIGURATION_INSTANTIATION_LOCK.synchronized {
        logDebug("Cloning Hadoop Configuration")
        val newJobConf = new JobConf(conf)
        if (!conf.isInstanceOf[JobConf]) {
          initLocalJobConfFuncOpt.foreach(f => f(newJobConf))
        }
        newJobConf
      }
    } else {
      if (conf.isInstanceOf[JobConf]) {
        logDebug("Re-using user-broadcasted JobConf")
        conf.asInstanceOf[JobConf]
      } else if (HadoopRDD.containsCachedMetadata(jobConfCacheKey)) {
        logDebug("Re-using cached JobConf")
        HadoopRDD.getCachedMetadata(jobConfCacheKey).asInstanceOf[JobConf]
      } else {
        // Create a JobConf that will be cached and used across this RDD's getJobConf() calls in the
        // local process. The local cache is accessed through HadoopRDD.putCachedMetadata().
        // The caching helps minimize GC, since a JobConf can contain ~10KB of temporary objects.
        // Synchronize to prevent ConcurrentModificationException (SPARK-1097, HADOOP-10456).
        HadoopRDD.CONFIGURATION_INSTANTIATION_LOCK.synchronized {
          logDebug("Creating new JobConf and caching it for later re-use")
          val newJobConf = new JobConf(conf)
          initLocalJobConfFuncOpt.foreach(f => f(newJobConf))
          HadoopRDD.putCachedMetadata(jobConfCacheKey, newJobConf)
          newJobConf
        }
      }
    }
  }

  protected def getInputFormat(conf: JobConf): InputFormat[K, V] = {
    val newInputFormat = ReflectionUtils.newInstance(inputFormatClass.asInstanceOf[Class[_]], conf)
      .asInstanceOf[InputFormat[K, V]]
    newInputFormat match {
      case c: Configurable => c.setConf(conf)
      case _ =>
    }
    newInputFormat
  }

  override def getPartitions: Array[Partition] = {
    val jobConf = getJobConf()
    // add the credentials here as this can be called before SparkContext initialized
    // 进行Hadoop安全验证的配置
    SparkHadoopUtil.get.addCredentials(jobConf)
    // 获取Hadoop的InputFormat
    val inputFormat = getInputFormat(jobConf)
    // 根据InputFormat获取数据分片
    val inputSplits = inputFormat.getSplits(jobConf, minPartitions)
    // 定义存放分区的数组
    val array = new Array[Partition](inputSplits.size)
    // 遍历所有数据分片，为每个数据分片构造HadoopPartition
    for (i <- 0 until inputSplits.size) {
      // id为当前HadoopRDD的ID，i为分区的索引
      array(i) = new HadoopPartition(id, i, inputSplits(i))
    }
    array
  }

  override def compute(theSplit: Partition, context: TaskContext): InterruptibleIterator[(K, V)] = {
    // 创建一个NextIterator迭代器
    val iter = new NextIterator[(K, V)] {

      // 将theSplit参数转换为HadoopPartition类型
      private val split = theSplit.asInstanceOf[HadoopPartition]
      logInfo("Input split: " + split.inputSplit)
      // 获取Hadoop的Configuration配置对象，JobConf继承自Hadoop的org.apache.hadoop.conf.Configuration
      private val jobConf = getJobConf()

      // 度量信息
      private val inputMetrics = context.taskMetrics().inputMetrics
      private val existingBytesRead = inputMetrics.bytesRead

      // Sets the thread local variable for the file's name
      // 对分区的InputSplit序列化分片数据进行匹配
      split.inputSplit.value match {
        // 设置分片所在文件的路径到InputFileNameHolder中进行记录
        case fs: FileSplit => InputFileNameHolder.setInputFileName(fs.getPath.toString)
        case _ => InputFileNameHolder.unsetInputFileName()
      }

      // Find a function that will return the FileSystem bytes read by this thread. Do this before
      // creating RecordReader, because RecordReader's constructor might read some bytes
      // 寻找用于读取数据的回调函数
      private val getBytesReadCallback: Option[() => Long] = split.inputSplit.value match {
        case _: FileSplit | _: CombineFileSplit =>
          SparkHadoopUtil.get.getFSBytesReadOnThreadCallback()
        case _ => None
      }

      // For Hadoop 2.5+, we get our input bytes from thread-local Hadoop FileSystem statistics.
      // If we do a coalesce, however, we are likely to compute multiple partitions in the same
      // task and in the same thread, in which case we need to avoid override values written by
      // previous partitions (SPARK-13071).
      // 主要用于更新度量信息
      private def updateBytesRead(): Unit = {
        getBytesReadCallback.foreach { getBytesRead =>
          inputMetrics.setBytesRead(existingBytesRead + getBytesRead())
        }
      }

      // Hadoop的RecordReader，用于读取数据
      private var reader: RecordReader[K, V] = null
      // 获取InputFormat，读取本地文件时为TextInputFormat
      private val inputFormat = getInputFormat(jobConf)
      // 设置时间本地化配置
      HadoopRDD.addLocalConfiguration(
        new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(createTime),
        context.stageId, theSplit.index, context.attemptNumber, jobConf)

      reader = // 本地文件时，为LineRecordReader
        try {
          // 通过InputFormat获取RecordReader记录读取器
          inputFormat.getRecordReader(split.inputSplit.value, jobConf, Reporter.NULL)
        } catch {
          case e: IOException if ignoreCorruptFiles =>
            logWarning(s"Skipped the rest content in the corrupted file: ${split.inputSplit}", e)
            finished = true
            null
        }
      // Register an on-task-completion callback to close the input stream.
      // 为Task添加完成监听器，在Task完成时关闭迭代器
      context.addTaskCompletionListener{ context => closeIfNeeded() }
      // 读取的数据的键类型和值对象，对于LineRecordReader，键为LongWritable，表示偏移量，值为Text，表示一行的数据
      private val key: K = if (reader == null) null.asInstanceOf[K] else reader.createKey()
      private val value: V = if (reader == null) null.asInstanceOf[V] else reader.createValue()

      // 定义NextIterator的获取下一组数据的getNext()方法，用于读取下一组键值对数据
      override def getNext(): (K, V) = {
        try {
          // 通过RecordReader读取，返回值表示是否已读完
          finished = !reader.next(key, value)
        } catch {
          case e: IOException if ignoreCorruptFiles =>
            logWarning(s"Skipped the rest content in the corrupted file: ${split.inputSplit}", e)
            finished = true
        }
        if (!finished) {
          inputMetrics.incRecordsRead(1)
        }
        if (inputMetrics.recordsRead % SparkHadoopUtil.UPDATE_INPUT_METRICS_INTERVAL_RECORDS == 0) {
          updateBytesRead()
        }
        (key, value)
      }

      // 关闭方法
      override def close() {
        if (reader != null) {
          // 取消输入文件路径的记录
          InputFileNameHolder.unsetInputFileName()
          // Close the reader and release it. Note: it's very important that we don't close the
          // reader more than once, since that exposes us to MAPREDUCE-5918 when running against
          // Hadoop 1.x and older Hadoop 2.x releases. That bug can lead to non-deterministic
          // corruption issues when reading compressed input.
          try {
            reader.close()
          } catch {
            case e: Exception =>
              if (!ShutdownHookManager.inShutdown()) {
                logWarning("Exception in RecordReader.close()", e)
              }
          } finally {
            reader = null
          }
          if (getBytesReadCallback.isDefined) {
            updateBytesRead()
          } else if (split.inputSplit.value.isInstanceOf[FileSplit] ||
                     split.inputSplit.value.isInstanceOf[CombineFileSplit]) {
            // If we can't get the bytes read from the FS stats, fall back to the split size,
            // which may be inaccurate.
            try {
              inputMetrics.incBytesRead(split.inputSplit.value.getLength)
            } catch {
              case e: java.io.IOException =>
                logWarning("Unable to get input size to set InputMetrics for task", e)
            }
          }
        }
      }
    }

    /**
     * 将创建的NextIterator迭代器包装为InterruptibleIterator
     * 该迭代器添加了中断功能，可以通过TaskContxt是否被中断来决定是否中断迭代操作
     */
    new InterruptibleIterator[(K, V)](context, iter)
  }

  /** Maps over a partition, providing the InputSplit that was used as the base of the partition. */
  @DeveloperApi
  def mapPartitionsWithInputSplit[U: ClassTag](
      f: (InputSplit, Iterator[(K, V)]) => Iterator[U],
      preservesPartitioning: Boolean = false): RDD[U] = {
    new HadoopMapPartitionsWithSplitRDD(this, f, preservesPartitioning)
  }

  override def getPreferredLocations(split: Partition): Seq[String] = {
    val hsplit = split.asInstanceOf[HadoopPartition].inputSplit.value
    val locs: Option[Seq[String]] = HadoopRDD.SPLIT_INFO_REFLECTIONS match {
      case Some(c) =>
        try {
          val lsplit = c.inputSplitWithLocationInfo.cast(hsplit)
          val infos = c.getLocationInfo.invoke(lsplit).asInstanceOf[Array[AnyRef]]
          HadoopRDD.convertSplitLocationInfo(infos)
        } catch {
          case e: Exception =>
            logDebug("Failed to use InputSplitWithLocations.", e)
            None
        }
      case None => None
    }
    locs.getOrElse(hsplit.getLocations.filter(_ != "localhost"))
  }

  override def checkpoint() {
    // Do nothing. Hadoop RDD should not be checkpointed.
  }

  override def persist(storageLevel: StorageLevel): this.type = {
    if (storageLevel.deserialized) {
      logWarning("Caching HadoopRDDs as deserialized objects usually leads to undesired" +
        " behavior because Hadoop's RecordReader reuses the same Writable object for all records." +
        " Use a map transformation to make copies of the records.")
    }
    super.persist(storageLevel)
  }

  def getConf: Configuration = getJobConf()
}

private[spark] object HadoopRDD extends Logging {
  /**
   * Configuration's constructor is not threadsafe (see SPARK-1097 and HADOOP-10456).
   * Therefore, we synchronize on this lock before calling new JobConf() or new Configuration().
   */
  val CONFIGURATION_INSTANTIATION_LOCK = new Object()

  /** Update the input bytes read metric each time this number of records has been read */
  val RECORDS_BETWEEN_BYTES_READ_METRIC_UPDATES = 256

  /**
   * The three methods below are helpers for accessing the local map, a property of the SparkEnv of
   * the local process.
   */
  def getCachedMetadata(key: String): Any = SparkEnv.get.hadoopJobMetadata.get(key)

  def containsCachedMetadata(key: String): Boolean = SparkEnv.get.hadoopJobMetadata.containsKey(key)

  private def putCachedMetadata(key: String, value: Any): Unit =
    SparkEnv.get.hadoopJobMetadata.put(key, value)

  /** Add Hadoop configuration specific to a single partition and attempt. */
  def addLocalConfiguration(jobTrackerId: String, jobId: Int, splitId: Int, attemptId: Int,
                            conf: JobConf) {
    val jobID = new JobID(jobTrackerId, jobId)
    val taId = new TaskAttemptID(new TaskID(jobID, TaskType.MAP, splitId), attemptId)

    conf.set("mapred.tip.id", taId.getTaskID.toString)
    conf.set("mapred.task.id", taId.toString)
    conf.setBoolean("mapred.task.is.map", true)
    conf.setInt("mapred.task.partition", splitId)
    conf.set("mapred.job.id", jobID.toString)
  }

  /**
   * Analogous to [[org.apache.spark.rdd.MapPartitionsRDD]], but passes in an InputSplit to
   * the given function rather than the index of the partition.
   */
  private[spark] class HadoopMapPartitionsWithSplitRDD[U: ClassTag, T: ClassTag](
      prev: RDD[T],
      f: (InputSplit, Iterator[T]) => Iterator[U],
      preservesPartitioning: Boolean = false)
    extends RDD[U](prev) {

    override val partitioner = if (preservesPartitioning) firstParent[T].partitioner else None

    override def getPartitions: Array[Partition] = firstParent[T].partitions

    override def compute(split: Partition, context: TaskContext): Iterator[U] = {
      val partition = split.asInstanceOf[HadoopPartition]
      val inputSplit = partition.inputSplit.value
      f(inputSplit, firstParent[T].iterator(split, context))
    }
  }

  private[spark] class SplitInfoReflections {
    val inputSplitWithLocationInfo =
      Utils.classForName("org.apache.hadoop.mapred.InputSplitWithLocationInfo")
    val getLocationInfo = inputSplitWithLocationInfo.getMethod("getLocationInfo")
    val newInputSplit = Utils.classForName("org.apache.hadoop.mapreduce.InputSplit")
    val newGetLocationInfo = newInputSplit.getMethod("getLocationInfo")
    val splitLocationInfo = Utils.classForName("org.apache.hadoop.mapred.SplitLocationInfo")
    val isInMemory = splitLocationInfo.getMethod("isInMemory")
    val getLocation = splitLocationInfo.getMethod("getLocation")
  }

  private[spark] val SPLIT_INFO_REFLECTIONS: Option[SplitInfoReflections] = try {
    Some(new SplitInfoReflections)
  } catch {
    case e: Exception =>
      logDebug("SplitLocationInfo and other new Hadoop classes are " +
          "unavailable. Using the older Hadoop location info code.", e)
      None
  }

  private[spark] def convertSplitLocationInfo(infos: Array[AnyRef]): Option[Seq[String]] = {
    Option(infos).map(_.flatMap { loc =>
      val reflections = HadoopRDD.SPLIT_INFO_REFLECTIONS.get
      val locationStr = reflections.getLocation.invoke(loc).asInstanceOf[String]
      if (locationStr != "localhost") {
        if (reflections.isInMemory.invoke(loc).asInstanceOf[Boolean]) {
          logDebug(s"Partition $locationStr is cached by Hadoop.")
          Some(HDFSCacheTaskLocation(locationStr).toString)
        } else {
          Some(HostTaskLocation(locationStr).toString)
        }
      } else {
        None
      }
    })
  }
}

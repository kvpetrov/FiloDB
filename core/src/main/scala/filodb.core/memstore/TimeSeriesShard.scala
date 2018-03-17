package filodb.core.memstore

import java.util.concurrent.TimeUnit

import scala.collection.mutable.{HashMap, HashSet}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import com.googlecode.javaewah.{EWAHCompressedBitmap, IntIterator}
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import kamon.Kamon
import kamon.metric.MeasurementUnit
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

import filodb.core._
import filodb.core.Types.{ChunkID, PartitionKey}
import filodb.core.metadata.Dataset
import filodb.core.store._
import filodb.memory._
import filodb.memory.format.{SchemaRowReader, UnsafeUtils, ZeroCopyUTF8String => UTF8Str}


class TimeSeriesShardStats(dataset: DatasetRef, shardNum: Int) {
  val tags = Map("shard" -> shardNum.toString, "dataset" -> dataset.toString)

  val rowsIngested = Kamon.counter("memstore-rows-ingested").refine(tags)
  val partitionsCreated = Kamon.counter("memstore-partitions-created").refine(tags)
  val rowsSkipped  = Kamon.counter("recovery-row-skipped").refine(tags)
  val numChunksEncoded = Kamon.counter("memstore-chunks-encoded").refine(tags)
  val numSamplesEncoded = Kamon.counter("memstore-samples-encoded").refine(tags)
  val encodedBytes  = Kamon.counter("memstore-encoded-bytes-allocated", MeasurementUnit.information.bytes).refine(tags)
  val flushesSuccessful = Kamon.counter("memstore-flushes-success").refine(tags)
  val flushesFailedPartWrite = Kamon.counter("memstore-flushes-failed-partition").refine(tags)
  val flushesFailedChunkWrite = Kamon.counter("memstore-flushes-failed-chunk").refine(tags)
  val flushesFailedOther = Kamon.counter("memstore-flushes-failed-other").refine(tags)

  /**
   * These gauges are intended to be combined with one of the latest offset of Kafka partitions so we can produce
   * stats on message lag:
   *   kafka_ingestion_lag = kafka_latest_offset - offsetLatestInMem
   *   memstore_ingested_to_persisted_lag = offsetLatestInMem - offsetLatestFlushed
   *   etc.
   *
   * NOTE: only positive offsets will be recorded.  Kafka does not give negative offsets, but Kamon cannot record
   * negative numbers either.
   * The "latest" vs "earliest" flushed reflects that there are really n offsets, one per flush group.
   */
  val offsetLatestInMem = Kamon.gauge("shard-offset-latest-inmemory").refine(tags)
  val offsetLatestFlushed = Kamon.gauge("shard-offset-flushed-latest").refine(tags)
  val offsetEarliestFlushed = Kamon.gauge("shard-offset-flushed-earliest").refine(tags)
  val numPartitions = Kamon.gauge("num-partitions").refine(tags)

  val partitionsPagedFromColStore = Kamon.counter("memstore-partitions-paged-in").refine(tags)
  val chunkIdsPagedFromColStore = Kamon.counter("memstore-chunkids-paged-in").refine(tags)
  val chunkIdsEvicted = Kamon.counter("memstore-chunkids-evicted").refine(tags)
  val partitionsQueried = Kamon.counter("memstore-partitions-queried").refine(tags)
  val numChunksQueried = Kamon.counter("memstore-chunks-queried").refine(tags)
  val partitionsEvicted = Kamon.counter("memstore-partitions-evicted").refine(tags)
  val memoryStats = new MemoryStats(tags)

  val bufferPoolSize = Kamon.gauge("memstore-writebuffer-pool-size").refine(tags)
  val indexEntries = Kamon.gauge("memstore-index-entries").refine(tags)
  val indexBytes   = Kamon.gauge("memstore-index-bytes").refine(tags)
}

object TimeSeriesShard {
  // The allocation size for each piece of metadata (for each chunkset) in a Block.  For now it is just
  // the integer partition ID and the chunkID
  val BlockMetaAllocSize: Short = 12

  def writeMeta(addr: Long, partitionID: Int, chunkID: ChunkID): Unit = {
    UnsafeUtils.setInt(UnsafeUtils.ZeroPointer, addr, partitionID)
    UnsafeUtils.setLong(UnsafeUtils.ZeroPointer, addr + 4, chunkID)
  }
}

/**
  * Contains all of the data for a SINGLE shard of a time series oriented dataset.
  *
  * Each partition has an integer ID which is used for bitmap indexing using PartitionKeyIndex.
  * Within a shard, the partitions are grouped into a fixed number of groups to facilitate persistence and recovery:
  * - groups spread out the persistence/flushing load across time
  * - having smaller batches of flushes shortens the window of recovery and enables skipping of records/less CPU
  *
  * Each incoming time series is hashed into a group.  Each group has its own watermark.  The watermark indicates,
  * for that group, up to what offset incoming records for that group has been persisted.  At recovery time, records
  * that fall below the watermark for that group will be skipped (since they can be recovered from disk).
  */
class TimeSeriesShard(dataset: Dataset,
                      config: Config,
                      val shardNum: Int,
                      sink: ColumnStore,
                      metastore: MetaStore,
                      evictionPolicy: PartitionEvictionPolicy)
                     (implicit val ec: ExecutionContext) extends StrictLogging {
  import collection.JavaConverters._
  import TimeSeriesShard._

  val shardStats = new TimeSeriesShardStats(dataset.ref, shardNum)

  /**
    * List of all partitions in the shard stored in memory, indexed by partition ID
    * TODO: We set accessOrder so that we can iterate in reverse order (least accessed first)
    *       This breaks a bunch of tests.  So do this later.
    */
  private final val partitions = new java.util.LinkedHashMap[Int, TimeSeriesPartition] // (50000, 0.75F, true)

  /**
   * next partition ID number
   */
  private var nextPartitionID = 0

  /**
    * Hash Map from Partition Key to Partition
    */
  private final val keyMap = new HashMap[SchemaRowReader, TimeSeriesPartition]

  /**
    * This index helps identify which partitions have any given column-value.
    * Used to answer queries not involving the full partition key.
    * Maintained using a high-performance bitmap index.
    */
  private final val keyIndex = new PartitionKeyIndex(dataset)

  /**
    * Keeps track of count of rows ingested into memstore, not necessarily flushed.
    * This is generally used to report status and metrics.
    */
  private final var ingested = 0L

  /**
    * Keeps track of last offset ingested into memory (not necessarily flushed).
    * This value is used to keep track of the checkpoint to be written for next flush for any group.
    */
  private final var _offset = Long.MinValue

  private val reclaimListener = new ReclaimListener {
    def onReclaim(metaAddr: Long, numBytes: Int): Unit = {
      assert(numBytes == BlockMetaAllocSize)
      val partID = UnsafeUtils.getInt(metaAddr)
      val chunkID = UnsafeUtils.getLong(metaAddr + 4)
      val partition = partitions.get(partID)
      if (partition != UnsafeUtils.ZeroPointer) {
        partition.removeChunksAt(chunkID)
      }
    }
  }

  private val maxChunksSize = config.getInt("memstore.max-chunks-size")
  private val shardMemoryMB = config.getInt("memstore.shard-memory-mb")
  private val numPagesPerBlock = Try(config.getInt("memstore.num-block-pages")).getOrElse(1000)
  private final val numGroups = config.getInt("memstore.groups-per-shard")
  private val maxNumPartitions = config.getInt("memstore.max-num-partitions")
  private val chunkRetentionHours = config.getDuration("memstore.demand-paged-chunk-retention-period",
    TimeUnit.HOURS).toInt

  // The off-heap block store used for encoded chunks
  protected val blockMemorySize: Long = shardMemoryMB * 1024 * 1024L
  private val blockStore = new PageAlignedBlockManager(blockMemorySize, shardStats.memoryStats, reclaimListener,
                                                       numPagesPerBlock, chunkRetentionHours)
  private val blockFactoryPool = new BlockMemFactoryPool(blockStore, BlockMetaAllocSize)
  private val numColumns = dataset.dataColumns.size

  // The off-heap buffers used for ingesting the newest data samples
  protected val bufferMemorySize: Long = maxChunksSize * 8L * maxNumPartitions * numColumns
  logger.info(s"Allocating $bufferMemorySize bytes for WriteBufferPool for shard $shardNum")
  protected val bufferMemoryManager = new NativeMemoryManager(bufferMemorySize)
  protected val pagedChunkStore = new DemandPagedChunkStore(dataset, blockStore, BlockMetaAllocSize,
                                                            chunkRetentionHours, shardNum)

  /**
    * Unencoded/unoptimized ingested data is stored in buffers that are allocated from this off-heap pool
    * Set initial size to a fraction of the max chunk size, so that partitions with sparse amount of data
    * will not cause too much memory bloat.  GrowableVector allows vectors to grow, so this should be OK
    */
  private val bufferPool = new WriteBufferPool(bufferMemoryManager, dataset, maxChunksSize / 8, maxNumPartitions)
  logger.info(s"Finished initializing memory pools for shard $shardNum")

  private final val partitionGroups = Array.fill(numGroups)(new EWAHCompressedBitmap)

  /**
    * The offset up to and including the last record in this group to be successfully persisted.
    * Also used during recovery to figure out what incoming records to skip (since it's persisted)
    */
  private final val groupWatermark = Array.fill(numGroups)(Long.MinValue)

  /**
    * This holds two bitmap indexes per group and tracks the partition keys that are pending
    * flush to the persistent sink's partition list tracker. When one bitmap is being updated with ingested partitions,
    * the other is being flushed. When buffers are switched for the group, the indexes are swapped.
    */
  private final val partKeysToFlush = Array.fill(numGroups, 2)(new EWAHCompressedBitmap)

  val queryScheduler = monix.execution.Scheduler.computation()

  class PartitionIterator(intIt: IntIterator) extends Iterator[TimeSeriesPartition] {
    var nextPart = UnsafeUtils.ZeroPointer.asInstanceOf[TimeSeriesPartition]
    def hasNext: Boolean = {
      while (intIt.hasNext && nextPart == UnsafeUtils.ZeroPointer) {
        nextPart = partitions.get(intIt.next)
      }
      nextPart != UnsafeUtils.ZeroPointer
    }
    def next: TimeSeriesPartition = {
      val toReturn = nextPart
      nextPart = UnsafeUtils.ZeroPointer.asInstanceOf[TimeSeriesPartition]    // reset so that hasNext can keep going
      toReturn
    }
  }

  def ingest(rows: Seq[IngestRecord]): Long = {
    // now go through each row, find the partition and call partition ingest
    var numActuallyIngested = 0
    rows.foreach { case IngestRecord(partKey, data, offset) =>
      // RECOVERY: Check the watermark for the group that this record is part of.  If the offset is < watermark,
      // then do not bother with the expensive partition key comparison and ingestion.  Just skip it
      if (offset < groupWatermark(group(partKey))) {
        shardStats.rowsSkipped.increment
      } else {
        val partition = keyMap.getOrElse(partKey, addPartition(partKey, true))
        partition.ingest(data, offset)
        numActuallyIngested += 1
      }
    }
    shardStats.rowsIngested.increment(numActuallyIngested)
    ingested += numActuallyIngested
    if (rows.nonEmpty) _offset = rows.last.offset
    _offset
  }

  def indexNames: Iterator[String] = keyIndex.indexNames

  def indexValues(indexName: String): Iterator[UTF8Str] = keyIndex.indexValues(indexName)

  def numRowsIngested: Long = ingested

  def numActivePartitions: Int = keyMap.size

  def latestOffset: Long = _offset

  /**
    * Sets the watermark for each subgroup.  If an ingested record offset is below this watermark then it will be
    * assumed to already have been persisted, and the record will be discarded.  Use only for recovery.
    * @param watermarks a Map from group number to watermark
    */
  def setGroupWatermarks(watermarks: Map[Int, Long]): Unit =
    watermarks.foreach { case (group, mark) => groupWatermark(group) = mark }

  private final def group(partKey: SchemaRowReader): Int = Math.abs(partKey.hashCode % numGroups)

  // Rapidly switch all of the input buffers for a particular group
  // Preferably this is done in the same thread/stream as input records to avoid concurrency issues
  // and ensure that all the partitions in a group are switched at the same watermark
  def switchGroupBuffers(groupNum: Int): Unit = {
    logger.debug(s"Switching write buffers for group $groupNum in shard $shardNum")
    new PartitionIterator(partitionGroups(groupNum).intIterator).foreach(_.switchBuffers())

    // swap the partKeysToFlush bitmap indexes too. Clear the one that has been flushed
    val temp = partKeysToFlush(groupNum)(0)
    partKeysToFlush(groupNum)(0) = partKeysToFlush(groupNum)(1)
    partKeysToFlush(groupNum)(1) = temp
    partKeysToFlush(groupNum)(0).clear()
  }

  def createFlushTask(flushGroup: FlushGroup)(implicit ingestionScheduler: Scheduler): Task[Response] = {
    val tracer = Kamon.buildSpan("chunk-flush-task-latency-after-retries").start() // TODO tags: shardStats.tags
    val taskToReturn = partitionGroups(flushGroup.groupNum).isEmpty match {
      case false =>
        val partitionIt = new PartitionIterator(partitionGroups(flushGroup.groupNum).intIterator)
        doFlushSteps(flushGroup, partitionIt)
      case true =>
        // even though there were no records for the chunkset, we want to write checkpoint anyway
        // since we should not resume from earlier checkpoint for the group
        Task.fromFuture(commitCheckpoint(dataset.ref, shardNum, flushGroup))
    }
    taskToReturn.runOnComplete(_ => tracer.finish())
    shardStats.bufferPoolSize.set(bufferPool.poolSize)
    shardStats.indexEntries.set(keyIndex.indexSize)
    shardStats.indexBytes.set(keyIndex.indexBytes)
    taskToReturn
  }

  private def doFlushSteps(flushGroup: FlushGroup,
                           partitionIt: Iterator[TimeSeriesPartition]): Task[Response] = {
    // Only allocate the blockHolder when we actually have chunks/partitions to flush
    val blockHolder = blockFactoryPool.checkout()
    // Given the flush group, create an observable of ChunkSets
    val chunkSetIt = partitionIt.flatMap(_.makeFlushChunks(blockHolder))

    // Note that all cassandra writes will have included retries. Failures after retries will imply data loss
    // in order to keep the ingestion moving. It is important that we don't fall back far behind.
    val chunkSetStream = Observable.fromIterator(chunkSetIt)
    logger.debug(s"Created flush ChunkSets stream for group ${flushGroup.groupNum} in shard $shardNum")
    // Write the stream and partition keys to the sink, checkpoint, mark buffers as eligible for cleanup
    val partKeysInGroup =
      new PartitionIterator(partKeysToFlush(flushGroup.groupNum)(1).intIterator()).map(_.binPartition)

    val writeChunksFuture = sink.write(dataset, chunkSetStream).recover { case e =>
      logger.error("Critical! Chunk persistence failed after retries and skipped", e)
      shardStats.flushesFailedChunkWrite.increment
      DataDropped
    }
    val writePartKeyFuture = sink.addPartitions(dataset, partKeysInGroup, shardNum).recover { case e =>
      logger.error("Critical! Partition Key(s) persistence failed after retries and skipped", e)
      shardStats.flushesFailedPartWrite.increment
      DataDropped
    }

    // If the above futures fail with ErrorResponse because of DB failures, skip the chunk.
    // Sorry - need to drop the data to keep the ingestion moving
    val taskFuture = Future.sequence(Seq(writeChunksFuture, writePartKeyFuture)).flatMap {
      case Seq(Success, Success)     => commitCheckpoint(dataset.ref, shardNum, flushGroup)
      // No chunks written but partition keys flushed?  Don't checkpoint in this case
      case Seq(NotApplied, Success)  => Future.successful(NotApplied)
      case Seq(er: ErrorResponse, _) => Future.successful(er)
      case Seq(_, er: ErrorResponse) => Future.successful(er)
    }.map { case resp =>
      logger.info(s"Flush of shard=$shardNum group=$flushGroup response=$resp offset=${_offset}")
      blockHolder.markUsedBlocksReclaimable()
      blockFactoryPool.release(blockHolder)
      resp
    }.recover { case e =>
      logger.error("Internal Error - should have not reached this state", e)
      blockFactoryPool.release(blockHolder)
      DataDropped
    }
    Task.fromFuture(taskFuture)
  }

  private def commitCheckpoint(ref: DatasetRef, shardNum: Int, flushGroup: FlushGroup): Future[Response] =
    // negative checkpoints are refused by Kafka, and also offsets should be positive
    if (flushGroup.flushWatermark > 0) {
      val fut = metastore.writeCheckpoint(ref, shardNum, flushGroup.groupNum, flushGroup.flushWatermark).map { r =>
        shardStats.flushesSuccessful.increment
        r
      }.recover { case e =>
        logger.error("Critical! Checkpoint persistence skipped", e)
        shardStats.flushesFailedOther.increment
        // skip the checkpoint write
        // Sorry - need to skip to keep the ingestion moving
        DataDropped
      }
      // Update stats
      if (_offset >= 0) shardStats.offsetLatestInMem.set(_offset)
      groupWatermark(flushGroup.groupNum) = flushGroup.flushWatermark
      val maxWatermark = groupWatermark.max
      val minWatermark = groupWatermark.min
      if (maxWatermark >= 0) shardStats.offsetLatestFlushed.set(maxWatermark)
      if (minWatermark >= 0) shardStats.offsetEarliestFlushed.set(minWatermark)
      fut
    } else {
      Future.successful(NotApplied)
    }

  // Creates a new TimeSeriesPartition, updating indexes
  // NOTE: it's important to use an actual BinaryRecord instead of just a RowReader in the internal
  // data structures.  The translation to BinaryRecord only happens here (during first time creation
  // of a partition) and keeps internal data structures from having to keep copies of incoming records
  // around, which might be much more expensive memory wise.  One consequence though is that internal
  // and external partition key components need to yield the same hashCode.  IE, use UTF8Strings everywhere.
  def addPartition(newPartKey: SchemaRowReader, needsPersistence: Boolean): TimeSeriesPartition = {
    checkAndEvictPartitions()
    val binPartKey = dataset.partKey(newPartKey)
    val newPart = new TimeSeriesPartition(nextPartitionID, dataset, binPartKey, shardNum, sink,
                        bufferPool, config, needsPersistence, pagedChunkStore, shardStats)(queryScheduler)
    keyIndex.addKey(binPartKey, nextPartitionID)
    partitions.put(nextPartitionID, newPart)
    shardStats.partitionsCreated.increment
    keyMap(binPartKey) = newPart
    shardStats.numPartitions.increment()
    partitionGroups(group(newPartKey)).set(nextPartitionID)
    // if we are in the restore execution flow, we should not need to write this key
    if (needsPersistence) partKeysToFlush(group(binPartKey))(0).set(nextPartitionID)
    incrementPartitionID()
    newPart
  }

  // Ensures partition ID wraps around safely to 0, not negative numbers (which don't work with bitmaps)
  private def incrementPartitionID(): Unit = {
    nextPartitionID += 1
    if (nextPartitionID < 0) {
      nextPartitionID = 0
      logger.info(s"nextPartitionID has wrapped around to 0 again")
    }
    // Given that we have 2^31 range of partitionIDs, we should pretty much never run into this problem where
    // we wraparound and hit a previously used partitionID.  Actually dealing with this is not easy;  what if
    // the used one is still actively ingesting?  We need to solve the issue of evicting actively ingesting
    // partitions first.  For now, assert so at least we will catch this condition.
    require(!partitions.containsKey(nextPartitionID), s"Partition ID $nextPartitionID ran into existing partition")
  }

  /**
   * Check and evict partitions to free up memory and heap space.  NOTE: This should be called in the ingestion
   * stream so that there won't be concurrent other modifications.  Ideally this is called when trying to add partitions
   */
  private def checkAndEvictPartitions(): Unit = {
    val (prunedPartitions, indicesToPrune) = partitionsToEvict()
    if (!prunedPartitions.isEmpty) {
      logger.debug(s"About to prune ${prunedPartitions.cardinality} partitions...")

      // Prune indices so no more querying
      indicesToPrune.foreach { case (indexName, values) =>
        logger.debug(s"Pruning ${values.size} entries from index $indexName...")
        keyIndex.removeEntries(indexName, values.toSeq, prunedPartitions)
      }

      // Pruning group and flush bitmaps
      for { group <- 0 until numGroups } {
        partitionGroups(group) = partitionGroups(group).andNot(prunedPartitions)
        partKeysToFlush(group)(0) = partKeysToFlush(group)(0).andNot(prunedPartitions)
      }

      // Finally, prune partitions and keyMap data structures
      logger.info(s"Pruning ${prunedPartitions.cardinality} partitions from shard $shardNum")
      prunedPartitions.iterator.asScala.foreach { partId =>
        val partToRemove = partitions.get(partId)
        keyMap.remove(partToRemove.binPartition)
        partitions.remove(partId)
      }
      shardStats.partitionsEvicted.increment(prunedPartitions.cardinality)
    }
  }

  private def partitionsToEvict(): (EWAHCompressedBitmap, HashMap[UTF8Str, _ <: collection.Set[UTF8Str]]) = {
    // Iterate and add eligible partitions to delete to our list, plus list of indices and values to change
    val toEvict = evictionPolicy.howManyToEvict(keyMap.size)
    if (toEvict > 0) {
      // Iterate and add eligible partitions to delete to our list, plus list of indices and values to change
      val prunedPartitions = new EWAHCompressedBitmap()
      val indicesToPrune = new HashMap[UTF8Str, collection.mutable.Set[UTF8Str]]
      val partIt = partitions.entrySet.iterator
      // For some reason, the takeWhile method doesn't seem to work.
      while (partIt.hasNext && prunedPartitions.cardinality < toEvict) {
        val entry = partIt.next
        if (evictionPolicy.canEvict(entry.getValue)) {
          prunedPartitions.set(entry.getKey)
          keyIndex.getKeyIndexNamesValues(entry.getValue.binPartition).foreach { case (k, v) =>
            indicesToPrune.getOrElseUpdate(k, new HashSet[UTF8Str]) += v
          }
        }
      }
      (prunedPartitions, indicesToPrune)
    } else {
      (new EWAHCompressedBitmap(), HashMap.empty)
    }
  }

  private def getPartition(partKey: PartitionKey): Option[FiloPartition] = keyMap.get(partKey)

  def scanPartitions(partMethod: PartitionScanMethod): Observable[FiloPartition] = {
    val indexIt = partMethod match {
      case SinglePartitionScan(partition, _) =>
        getPartition(partition).map(Iterator.single).getOrElse(Iterator.empty)
      case MultiPartitionScan(partKeys, _)   =>
        partKeys.toIterator.flatMap(getPartition)
      case FilteredPartitionScan(split, filters) =>
        // TODO: Use filter func for columns not in index
        if (filters.nonEmpty) {
          val (indexIt, _) = keyIndex.parseFilters(filters)
          new PartitionIterator(indexIt)
        } else {
          partitions.values.iterator.asScala
        }
    }
    Observable.fromIterator(indexIt.map { p =>
      shardStats.partitionsQueried.increment()
      p
    })
  }

  /**
    * Reset all state in this shard.  Memory is not released as once released, then this class
    * cannot be used anymore.
    */
  def reset(): Unit = {
    logger.info(s"Clearing all MemStore state for shard $shardNum")
    partitions.clear()
    keyMap.clear()
    keyIndex.reset()
    ingested = 0L
    for { group <- 0 until numGroups } {
      partitionGroups(group) = new EWAHCompressedBitmap()
      groupWatermark(group) = Long.MinValue
    }
  }

  def shutdown(): Unit = {
    reset()   // Not really needed, but clear everything just to be consistent
    logger.info(s"Shutting down and releasing offheap memory for shard $shardNum")
    bufferMemoryManager.shutdown()
    blockStore.releaseBlocks()
  }
}
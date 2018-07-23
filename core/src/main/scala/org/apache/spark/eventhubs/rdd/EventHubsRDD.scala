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

package org.apache.spark.eventhubs.rdd

import com.microsoft.azure.eventhubs.EventData
import org.apache.spark.eventhubs.EventHubsConf
import org.apache.spark.eventhubs.client.{ CachedEventHubsReceiver, CachedReceiver }
import org.apache.spark.eventhubs.utils.SimulatedCachedReceiver
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.{ Partition, SparkContext, TaskContext }

/**
 * An [[RDD]] for consuming from Event Hubs.
 *
 * Starting and ending ranges are set before hand for improved
 * checkpointing, resiliency, and delivery-semantics.
 *
 * @param sc the [[SparkContext]]
 * @param ehConf the Event Hubs specific configurations
 * @param offsetRanges offset ranges that define which Event Hubs data
 *                     belongs to this RDD
 */
private[spark] class EventHubsRDD(sc: SparkContext,
                                  val ehConf: EventHubsConf,
                                  val offsetRanges: Array[OffsetRange])
    extends RDD[EventData](sc, Nil)
    with Logging
    with HasOffsetRanges {

  import org.apache.spark.eventhubs._

  override def getPartitions: Array[Partition] = {
    for { o <- offsetRanges.sortWith(_.partitionId < _.partitionId) } yield
      new EventHubsRDDPartition(o.partitionId,
                                o.nameAndPartition,
                                o.fromSeqNo,
                                o.untilSeqNo,
                                o.preferredLoc)
  }

  override def count: Long = offsetRanges.map(_.count).sum

  override def isEmpty(): Boolean = count == 0L

  override def take(num: Int): Array[EventData] = {
    val nonEmptyPartitions =
      this.partitions.map(_.asInstanceOf[EventHubsRDDPartition]).filter(_.count > 0)

    if (num < 1 || nonEmptyPartitions.isEmpty) {
      return Array()
    }

    val parts = nonEmptyPartitions.foldLeft(Map[Int, Int]()) { (result, part) =>
      val remain = num - result.values.sum
      if (remain > 0) {
        val taken = Math.min(remain, part.count)
        result + (part.index -> taken.toInt)

      } else {
        result
      }
    }

    context.runJob(
      this,
      (tc: TaskContext, it: Iterator[EventData]) => it.take(parts(tc.partitionId)).toArray,
      parts.keys.toArray
    ).flatten
  }

  override def getPreferredLocations(split: Partition): Seq[String] = {
    val part = split.asInstanceOf[EventHubsRDDPartition]
    part.preferredLoc.map(Seq(_)).getOrElse(Seq.empty)
  }

  private def errBeginAfterEnd(part: EventHubsRDDPartition): String =
    s"The beginning sequence number ${part.fromSeqNo} is larger than thet ending " +
      s"sequence number ${part.untilSeqNo} for EventHubs ${part.name} on partition " +
      s"${part.partitionId}."

  override def compute(partition: Partition, context: TaskContext): Iterator[EventData] = {
    val part = partition.asInstanceOf[EventHubsRDDPartition]
    assert(part.fromSeqNo <= part.untilSeqNo, errBeginAfterEnd(part))

    if (part.fromSeqNo == part.untilSeqNo) {
      logInfo(
        s"Beginning sequence number ${part.fromSeqNo} is equal to the ending sequence " +
          s"number ${part.untilSeqNo}. Returning empty partition for EH: ${part.name} " +
          s"on partition: ${part.partitionId}")
      Iterator.empty
    } else {
      new EventHubsRDDIterator(part, context)
    }
  }

  /**
   * An iterator which consumes events from Event Hubs for the ranges specified in the
   * partition.
   *
   * [[CachedEventHubsReceiver]] is always used for consumption to take advantage of
   * pre-fetching. For receiver cache hits, consumption is essentially an in-memory
   * de-queue. For cache misses, a receiver needs to be created and we wait for
   * events to be sent over the wire.
   *
   * @param part the partition for which events will be consumed
   * @param context the [[TaskContext]]
   */
  private class EventHubsRDDIterator(part: EventHubsRDDPartition, context: TaskContext)
      extends Iterator[EventData] {

    val cachedReceiver: CachedReceiver = if (ehConf.useSimulatedClient) {
      SimulatedCachedReceiver
    } else {
      CachedEventHubsReceiver
    }

    logInfo(
      s"Computing EventHubs ${part.name}, partition ${part.partitionId} " +
        s"sequence numbers ${part.fromSeqNo} => ${part.untilSeqNo}")

    var requestSeqNo: SequenceNumber = part.fromSeqNo

    override def hasNext(): Boolean = requestSeqNo < part.untilSeqNo

    override def next(): EventData = {
      assert(hasNext(), "Can't call next() once untilSeqNo has been reached.")
      val event = cachedReceiver.receive(ehConf,
                                         part.nameAndPartition,
                                         requestSeqNo,
                                         (part.untilSeqNo - part.fromSeqNo).toInt)
      requestSeqNo += 1
      event
    }
  }
}

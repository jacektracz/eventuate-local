package io.eventuate.local.java.kafka.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Processes a Kafka message and tracks the message offsets that have been successfully processed and can be committed
 */
public class KafkaMessageProcessor {
  private Logger logger = LoggerFactory.getLogger(getClass());

  private String subscriberId;
  private EventuateKafkaConsumerMessageHandler handler;
  private OffsetTracker offsetTracker = new OffsetTracker();

  private BlockingQueue<ConsumerRecord<String, String>> processedRecords = new LinkedBlockingQueue<>();
  private AtomicReference<KafkaMessageProcessorFailedException> failed = new AtomicReference<>();

  public KafkaMessageProcessor(String subscriberId, EventuateKafkaConsumerMessageHandler handler) {
    this.subscriberId = subscriberId;
    this.handler = handler;
  }


  public void process(ConsumerRecord<String, String> record) {
    throwFailureException();
    offsetTracker.noteUnprocessed(new TopicPartition(record.topic(), record.partition()), record.offset());
    handler.accept(record, (result, t) -> {
      if (t != null) {
        logger.error("Got exception: ", t);
        failed.set(new KafkaMessageProcessorFailedException(t));
      } else {
        logger.debug("Adding processed record to queue {} {}", subscriberId, record.offset());
        processedRecords.add(record);
      }
    });
  }

  void throwFailureException() {
    if (failed.get() != null)
      throw failed.get();
  }

  public Map<TopicPartition, OffsetAndMetadata> offsetsToCommit() {
    int count = 0;
    while (true) {
      ConsumerRecord<String, String> record = processedRecords.poll();
      if (record == null)
        break;
      count++;
      offsetTracker.noteProcessed(new TopicPartition(record.topic(), record.partition()), record.offset());
    }
    logger.trace("removed {} {} processed records from queue", subscriberId, count);
    return offsetTracker.offsetsToCommit();
  }

  public void noteOffsetsCommitted(Map<TopicPartition, OffsetAndMetadata> offsetsToCommit) {
    offsetTracker.noteOffsetsCommitted(offsetsToCommit);
  }

  public OffsetTracker getPending() {
    return offsetTracker;
  }

}

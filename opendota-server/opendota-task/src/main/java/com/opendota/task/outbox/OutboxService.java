package com.opendota.task.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Outbox 事件入队服务。
 *
 * <p>在同一 PG 事务内调用,写入 outbox_event 表。
 * <p>由 {@link OutboxRelayWorker} 异步投递到 Kafka。
 */
@Service
public class OutboxService {

    private final OutboxEventRepository outboxRepo;
    private final KafkaTopicProperties topicProps;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository outboxRepo,
                         KafkaTopicProperties topicProps,
                         ObjectMapper objectMapper) {
        this.outboxRepo = outboxRepo;
        this.topicProps = topicProps;
        this.objectMapper = objectMapper;
    }

    /**
     * 写入一条 outbox 事件(在调用方的事务内)。
     *
     * @param tenantId     租户 ID
     * @param aggregateType 聚合类型(如 task_definition)
     * @param aggregateId  聚合 ID(如 taskId)
     * @param eventType    事件类型(如 DispatchCommand)
     * @param payload      事件 payload 对象(将序列化为 JSON)
     * @param kafkaTopic   目标 Kafka topic
     * @param kafkaKey     Kafka 分区键
     */
    public void enqueue(String tenantId, String aggregateType, String aggregateId,
                        String eventType, Object payload, String kafkaTopic, String kafkaKey) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Outbox payload 序列化失败", e);
        }

        OutboxEvent event = new OutboxEvent(
                tenantId, aggregateType, aggregateId,
                eventType, payloadJson, kafkaTopic, kafkaKey);
        outboxRepo.save(event);
    }

    /**
     * 写入任务分发事件(便捷方法)。
     */
    public void enqueueDispatchCommand(String tenantId, String taskId, Object dispatchPayload) {
        enqueue(tenantId, "task_definition", taskId,
                "DispatchCommand", dispatchPayload,
                topicProps.getDispatchQueue(), taskId);
    }
}

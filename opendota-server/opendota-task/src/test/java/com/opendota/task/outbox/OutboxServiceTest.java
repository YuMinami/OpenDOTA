package com.opendota.task.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OutboxService 单元测试。
 */
class OutboxServiceTest {

    private OutboxEventRepository outboxRepo;
    private KafkaTopicProperties topicProps;
    private ObjectMapper objectMapper;
    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        outboxRepo = mock(OutboxEventRepository.class);
        topicProps = new KafkaTopicProperties();
        objectMapper = new ObjectMapper();
        outboxService = new OutboxService(outboxRepo, topicProps, objectMapper);
    }

    @Test
    void enqueue_savesEvent() {
        when(outboxRepo.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        Object payload = new TestPayload("tsk_123", "LSVWA234567890123");
        outboxService.enqueue("tenant-1", "task_definition", "tsk_123",
                "DispatchCommand", payload, "task-dispatch-queue", "tsk_123");

        verify(outboxRepo).save(argThat(event ->
                event.getTenantId().equals("tenant-1") &&
                event.getAggregateType().equals("task_definition") &&
                event.getAggregateId().equals("tsk_123") &&
                event.getEventType().equals("DispatchCommand") &&
                event.getKafkaTopic().equals("task-dispatch-queue") &&
                event.getKafkaKey().equals("tsk_123") &&
                "new".equals(event.getStatus())
        ));
    }

    @Test
    void enqueueDispatchCommand_usesDefaultTopic() {
        when(outboxRepo.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        Object payload = new TestPayload("tsk_456", "LSVWA234567890123");
        outboxService.enqueueDispatchCommand("tenant-1", "tsk_456", payload);

        verify(outboxRepo).save(argThat(event ->
                event.getKafkaTopic().equals("task-dispatch-queue") &&
                event.getKafkaKey().equals("tsk_456")
        ));
    }

    private record TestPayload(String taskId, String vin) {}
}

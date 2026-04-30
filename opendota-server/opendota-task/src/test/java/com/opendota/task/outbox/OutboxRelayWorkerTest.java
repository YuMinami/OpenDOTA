package com.opendota.task.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OutboxRelayWorker 单元测试。
 */
class OutboxRelayWorkerTest {

    private OutboxEventCustomRepository customRepo;
    private OutboxEventRepository outboxRepo;
    private JdbcTemplate jdbc;
    private KafkaTemplate<String, String> kafka;
    private OutboxMetrics metrics;
    private OutboxRelayWorker worker;

    @BeforeEach
    void setUp() {
        customRepo = mock(OutboxEventCustomRepository.class);
        outboxRepo = mock(OutboxEventRepository.class);
        jdbc = mock(JdbcTemplate.class);
        kafka = mock(KafkaTemplate.class);
        MeterRegistry registry = new SimpleMeterRegistry();
        metrics = new OutboxMetrics(registry);
        worker = new OutboxRelayWorker(customRepo, outboxRepo, jdbc, kafka, metrics);
    }

    @Test
    void drain_emptyBatch_noAction() {
        when(customRepo.fetchPending()).thenReturn(List.of());

        worker.drain();

        verify(kafka, never()).send(any(ProducerRecord.class));
        verify(outboxRepo, never()).markSent(anyLong(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void drain_successBatch_marksSent() {
        OutboxRow row = new OutboxRow(
                1L, "tenant-1", "task_definition", "tsk_123",
                "DispatchCommand", "{\"taskId\":\"tsk_123\"}",
                "task-dispatch-queue", "tsk_123", 0);
        when(customRepo.fetchPending()).thenReturn(List.of(row));

        SendResult<String, String> sendResult = mock(SendResult.class);
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(sendResult);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(future);

        worker.drain();

        verify(outboxRepo).markSent(eq(1L), any());
        assertEquals(1.0, metrics.sentCounter().count());
    }

    @SuppressWarnings("unchecked")
    @Test
    void drain_kafkaFailure_marksFailed() {
        OutboxRow row = new OutboxRow(
                2L, "tenant-1", "task_definition", "tsk_456",
                "DispatchCommand", "{\"taskId\":\"tsk_456\"}",
                "task-dispatch-queue", "tsk_456", 0);
        when(customRepo.fetchPending()).thenReturn(List.of(row));

        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Kafka 不可用"));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(future);

        worker.drain();

        verify(outboxRepo).markFailed(eq(2L), any(), contains("Kafka 不可用"));
        assertEquals(1.0, metrics.failedCounter().count());
    }

    @SuppressWarnings("unchecked")
    @Test
    void drain_partialFailure_continuesRemaining() {
        OutboxRow row1 = new OutboxRow(
                1L, "tenant-1", "task_definition", "tsk_1",
                "DispatchCommand", "{}", "task-dispatch-queue", "tsk_1", 0);
        OutboxRow row2 = new OutboxRow(
                2L, "tenant-1", "task_definition", "tsk_2",
                "DispatchCommand", "{}", "task-dispatch-queue", "tsk_2", 0);
        when(customRepo.fetchPending()).thenReturn(List.of(row1, row2));

        // 第一条失败,第二条成功
        CompletableFuture<SendResult<String, String>> failFuture = new CompletableFuture<>();
        failFuture.completeExceptionally(new RuntimeException("timeout"));
        SendResult<String, String> sendResult = mock(SendResult.class);
        CompletableFuture<SendResult<String, String>> successFuture = CompletableFuture.completedFuture(sendResult);

        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(failFuture)
                .thenReturn(successFuture);

        worker.drain();

        verify(outboxRepo).markFailed(eq(1L), any(), any());
        verify(outboxRepo).markSent(eq(2L), any());
    }
}

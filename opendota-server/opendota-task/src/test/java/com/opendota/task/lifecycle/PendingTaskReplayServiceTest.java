package com.opendota.task.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.task.outbox.KafkaTopicProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PendingTaskReplayService 单元测试 — 覆盖 Phase 4 DoD #3
 * 引入的 offline_task_push_latency 打点 + pending_online_at 清空。
 */
class PendingTaskReplayServiceTest {

    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private KafkaTemplate<String, String> kafka;
    private KafkaTopicProperties topicProps;
    private LifecycleMetrics metrics;
    private PendingTaskReplayService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        objectMapper = new ObjectMapper();
        kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        topicProps = new KafkaTopicProperties();
        topicProps.setDispatchQueue("task-dispatch-queue");
        MeterRegistry registry = new SimpleMeterRegistry();
        metrics = new LifecycleMetrics(registry);
        service = new PendingTaskReplayService(jdbc, objectMapper, kafka, topicProps, metrics);
    }

    @Test
    void replayPendingTasks_noPending_returnsZero() {
        when(jdbc.query(anyString(), any(RowMapper.class), anyString()))
                .thenReturn(List.of());

        int replayed = service.replayPendingTasks("LSVWA234567890123");

        assertEquals(0, replayed);
        verifyNoInteractions(kafka);
        assertEquals(0L, metrics.replayTriggeredCounter().count());
    }

    @Test
    void replayPendingTasks_singlePendingWithTimestamp_recordsLatencyAndClearsPendingOnlineAt() throws SQLException {
        long pendingMillis = System.currentTimeMillis() - 3_000L; // 3 秒前入队
        Timestamp pendingAt = new Timestamp(pendingMillis);
        Object[] rowData = {1L, "tsk_001", "tenant-1", "{\"ecuName\":\"VCU\"}", pendingAt,
                5, "batch", "batch", "{\"steps\":[]}", "abc123"};

        ResultSet rs = mockResultSet(rowData);
        RowMapper<?>[] capturedMapper = new RowMapper<?>[1];
        when(jdbc.query(anyString(), any(RowMapper.class), eq("LSVWA234567890123")))
                .thenAnswer(inv -> {
                    capturedMapper[0] = inv.getArgument(1);
                    return List.of(capturedMapper[0].mapRow(rs, 0));
                });

        int replayed = service.replayPendingTasks("LSVWA234567890123");

        assertEquals(1, replayed);
        // 记录 1 个延迟样本(3 秒,远 < 10s SLO)
        assertEquals(1L, metrics.offlineTaskPushLatencyTimer().count());
        double latencySeconds = metrics.offlineTaskPushLatencyTimer().totalTime(TimeUnit.SECONDS);
        assertTrue(latencySeconds >= 2.5 && latencySeconds <= 4.0,
                "延迟应在 ~3s 范围,实际=" + latencySeconds);
        // 触发 replay counter
        assertEquals(1.0, metrics.replayTriggeredCounter().count());
        // 清空 pending_online_at
        verify(jdbc).update(contains("SET pending_online_at = NULL"), eq(1L));
    }

    @Test
    void replayPendingTasks_pendingTimestampNull_skipsLatencySampleButStillReplays() throws SQLException {
        Object[] rowData = {2L, "tsk_002", "tenant-1", "{\"ecuName\":\"VCU\"}", null,
                5, "batch", "batch", "{\"steps\":[]}", "abc123"};

        ResultSet rs = mockResultSet(rowData);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("LSVWA234567890123")))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        int replayed = service.replayPendingTasks("LSVWA234567890123");

        assertEquals(1, replayed);
        assertEquals(0L, metrics.offlineTaskPushLatencyTimer().count(), "无 pending_online_at 应跳过 latency 打点");
        assertEquals(1.0, metrics.replayTriggeredCounter().count());
        verify(jdbc).update(contains("SET pending_online_at = NULL"), eq(2L));
    }

    @Test
    void replayPendingTasks_negativeLatency_clockSkew_skipsSample() throws SQLException {
        // 时钟回拨场景: pending_online_at 在未来
        Timestamp pendingAt = new Timestamp(System.currentTimeMillis() + 5_000L);
        Object[] rowData = {3L, "tsk_003", "tenant-1", "{}", pendingAt,
                5, "batch", "batch", "{}", "abc"};

        ResultSet rs = mockResultSet(rowData);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("LSVWA234567890123")))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        int replayed = service.replayPendingTasks("LSVWA234567890123");

        assertEquals(1, replayed);
        assertEquals(0L, metrics.offlineTaskPushLatencyTimer().count(), "负延迟应被跳过");
    }

    @Test
    void replayPendingTasks_kafkaTopicMatchesProperties() throws SQLException {
        Timestamp pendingAt = new Timestamp(System.currentTimeMillis() - 1_000L);
        Object[] rowData = {4L, "tsk_004", "tenant-1", "{}", pendingAt,
                5, "batch", "batch", "{}", "abc"};

        ResultSet rs = mockResultSet(rowData);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("LSVWA234567890123")))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        service.replayPendingTasks("LSVWA234567890123");

        verify(kafka).send(eq("task-dispatch-queue"), eq("tsk_004"), anyString());
    }

    /**
     * 仅 stub 出 PendingTaskReplayService.replayPendingTasks 内部使用的列。
     */
    private ResultSet mockResultSet(Object[] data) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("dispatch_id")).thenReturn((long) data[0]);
        when(rs.getString("task_id")).thenReturn((String) data[1]);
        when(rs.getString("tenant_id")).thenReturn((String) data[2]);
        when(rs.getString("ecu_scope")).thenReturn((String) data[3]);
        when(rs.getTimestamp("pending_online_at")).thenReturn((Timestamp) data[4]);
        when(rs.getInt("priority")).thenReturn((int) data[5]);
        when(rs.getString("schedule_type")).thenReturn((String) data[6]);
        when(rs.getString("payload_type")).thenReturn((String) data[7]);
        when(rs.getString("diag_payload")).thenReturn((String) data[8]);
        when(rs.getString("payload_hash")).thenReturn((String) data[9]);
        return rs;
    }
}

package com.opendota.task.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OnlineEventConsumer 单元测试。
 */
class OnlineEventConsumerTest {

    private ObjectMapper objectMapper;
    private VehicleOnlineService vehicleOnlineService;
    private PendingTaskReplayService replayService;
    private LifecycleMetrics metrics;
    private Acknowledgment ack;
    private OnlineEventConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        vehicleOnlineService = mock(VehicleOnlineService.class);
        replayService = mock(PendingTaskReplayService.class);
        MeterRegistry registry = new SimpleMeterRegistry();
        metrics = new LifecycleMetrics(registry);
        ack = mock(Acknowledgment.class);

        consumer = new OnlineEventConsumer(vehicleOnlineService, replayService, objectMapper, metrics);
    }

    @Test
    void onLifecycleEvent_connected_newOnline_triggersReplay() throws Exception {
        VehicleLifecycleEvent event = new VehicleLifecycleEvent(
                "LSVWA234567890123", "connected", "cid-001",
                "192.168.1.100:1883", "CN=LSVWA234567890123",
                Instant.now().toEpochMilli());

        when(vehicleOnlineService.markOnline(eq("LSVWA234567890123"), eq("cid-001"), any(Instant.class)))
                .thenReturn(1); // 状态变更

        ConsumerRecord<String, String> record = buildRecord(event);
        consumer.onLifecycleEvent(record, ack);

        verify(vehicleOnlineService).markOnline(eq("LSVWA234567890123"), eq("cid-001"), any(Instant.class));
        verify(replayService).replayPendingTasks("LSVWA234567890123");
        verify(ack).acknowledge();
        assertEquals(1, metrics.connectedCounter().count());
    }

    @Test
    void onLifecycleEvent_connected_alreadyOnline_noReplay() throws Exception {
        VehicleLifecycleEvent event = new VehicleLifecycleEvent(
                "LSVWA234567890123", "connected", "cid-001",
                "192.168.1.100:1883", "CN=LSVWA234567890123",
                Instant.now().toEpochMilli());

        when(vehicleOnlineService.markOnline(eq("LSVWA234567890123"), eq("cid-001"), any(Instant.class)))
                .thenReturn(0); // 已经在线,无变更

        ConsumerRecord<String, String> record = buildRecord(event);
        consumer.onLifecycleEvent(record, ack);

        verify(vehicleOnlineService).markOnline(eq("LSVWA234567890123"), eq("cid-001"), any(Instant.class));
        verifyNoInteractions(replayService); // 不触发回放
        verify(ack).acknowledge();
        assertEquals(1, metrics.connectedCounter().count());
    }

    @Test
    void onLifecycleEvent_disconnected_marksOffline() throws Exception {
        VehicleLifecycleEvent event = new VehicleLifecycleEvent(
                "LSVWA234567890123", "disconnected", "cid-001",
                "192.168.1.100:1883", "CN=LSVWA234567890123",
                Instant.now().toEpochMilli());

        when(vehicleOnlineService.markOffline(eq("LSVWA234567890123"), any(Instant.class)))
                .thenReturn(1);

        ConsumerRecord<String, String> record = buildRecord(event);
        consumer.onLifecycleEvent(record, ack);

        verify(vehicleOnlineService).markOffline(eq("LSVWA234567890123"), any(Instant.class));
        verifyNoInteractions(replayService);
        verify(ack).acknowledge();
        assertEquals(1, metrics.disconnectedCounter().count());
    }

    @Test
    void onLifecycleEvent_disconnected_alreadyOffline_noOp() throws Exception {
        VehicleLifecycleEvent event = new VehicleLifecycleEvent(
                "LSVWA234567890123", "disconnected", "cid-001",
                "192.168.1.100:1883", "CN=LSVWA234567890123",
                Instant.now().toEpochMilli());

        when(vehicleOnlineService.markOffline(eq("LSVWA234567890123"), any(Instant.class)))
                .thenReturn(0); // 已经离线

        ConsumerRecord<String, String> record = buildRecord(event);
        consumer.onLifecycleEvent(record, ack);

        verify(ack).acknowledge();
        assertEquals(1, metrics.disconnectedCounter().count());
    }

    @Test
    void onLifecycleEvent_unknownEvent_acknowledges() throws Exception {
        VehicleLifecycleEvent event = new VehicleLifecycleEvent(
                "LSVWA234567890123", "heartbeat", "cid-001",
                "192.168.1.100:1883", "CN=LSVWA234567890123",
                Instant.now().toEpochMilli());

        ConsumerRecord<String, String> record = buildRecord(event);
        consumer.onLifecycleEvent(record, ack);

        verify(ack).acknowledge();
        verifyNoInteractions(vehicleOnlineService);
    }

    @Test
    void onLifecycleEvent_invalidJson_skips() throws Exception {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "vehicle-lifecycle", 0, 0L, "LSVWA234567890123", "not-json!!!");

        consumer.onLifecycleEvent(record, ack);

        verify(ack).acknowledge();
        verifyNoInteractions(vehicleOnlineService);
    }

    @Test
    void onLifecycleEvent_exception_stillAcknowledges() throws Exception {
        VehicleLifecycleEvent event = new VehicleLifecycleEvent(
                "LSVWA234567890123", "connected", "cid-001",
                "192.168.1.100:1883", "CN=LSVWA234567890123",
                Instant.now().toEpochMilli());

        when(vehicleOnlineService.markOnline(anyString(), anyString(), any(Instant.class)))
                .thenThrow(new RuntimeException("DB 连接失败"));

        ConsumerRecord<String, String> record = buildRecord(event);
        consumer.onLifecycleEvent(record, ack);

        // 异常时仍然 ack,避免无限重试
        verify(ack).acknowledge();
    }

    // --- 辅助方法 ---

    private ConsumerRecord<String, String> buildRecord(VehicleLifecycleEvent event) throws Exception {
        return new ConsumerRecord<>(
                "vehicle-lifecycle", 0, 0L,
                event.vin(), objectMapper.writeValueAsString(event));
    }
}

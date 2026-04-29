package com.opendota.diag.cmd;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagCmdMetricsTest {

    private MeterRegistry registry;
    private DiagCmdMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new DiagCmdMetrics(registry);
    }

    @Test
    void recordDispatchShouldIncrementTotalAndTrackPending() {
        metrics.recordDispatch("msg-001");

        assertEquals(1.0d, registry.counter("dota_single_cmd_total").count());
        assertEquals(1, metrics.pendingCount());
    }

    @Test
    void recordResponseShouldRecordLatencyAndRemovePending() {
        metrics.recordDispatch("msg-001");
        metrics.recordResponse("msg-001", true);

        Timer timer = registry.find("dota_single_cmd_latency_seconds").timer();
        assertTrue(timer != null, "Timer dota_single_cmd_latency_seconds 应已注册");
        assertEquals(1, timer.count());
        assertEquals(0, metrics.pendingCount());
        assertEquals(1.0d, registry.counter("dota_single_cmd_success_total").count());
    }

    @Test
    void recordResponseWithFailureShouldNotIncrementSuccess() {
        metrics.recordDispatch("msg-002");
        metrics.recordResponse("msg-002", false);

        assertEquals(1.0d, registry.counter("dota_single_cmd_total").count());
        assertEquals(0.0d, registry.counter("dota_single_cmd_success_total").count());
    }

    @Test
    void recordResponseWithoutDispatchShouldNotThrow() {
        // 没有对应的 dispatch，不应抛异常
        metrics.recordResponse("unknown-msg", true);

        Timer timer = registry.find("dota_single_cmd_latency_seconds").timer();
        assertEquals(0, timer.count());
    }
}

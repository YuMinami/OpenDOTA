package com.opendota.task.lifecycle;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LifecycleMetrics 单元测试 — 覆盖 Phase 4 DoD #3 引入的
 * {@code offline_task_push_latency_seconds} Timer。
 */
class LifecycleMetricsTest {

    private MeterRegistry registry;
    private LifecycleMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new LifecycleMetrics(registry);
    }

    @Test
    void offlineTaskPushLatency_recordPositive_capturesSample() {
        metrics.recordOfflineTaskPushLatency(2_500L);

        assertEquals(1L, metrics.offlineTaskPushLatencyTimer().count());
        assertEquals(2_500.0, metrics.offlineTaskPushLatencyTimer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 0.5);
    }

    @Test
    void offlineTaskPushLatency_recordZero_skipped() {
        metrics.recordOfflineTaskPushLatency(0L);

        assertEquals(0L, metrics.offlineTaskPushLatencyTimer().count(), "0ms 样本应被跳过避免污染 P95");
    }

    @Test
    void offlineTaskPushLatency_recordNegative_skipped() {
        metrics.recordOfflineTaskPushLatency(-100L);

        assertEquals(0L, metrics.offlineTaskPushLatencyTimer().count(), "负值样本应被跳过");
    }

    @Test
    void offlineTaskPushLatency_p95UnderSloThreshold_passes() {
        // 模拟 100 车上线场景:99 条 < 5s,1 条 8s,P95 应 < 10s
        for (int i = 0; i < 99; i++) {
            metrics.recordOfflineTaskPushLatency(1_500L + i);
        }
        metrics.recordOfflineTaskPushLatency(8_000L);

        var snapshot = metrics.offlineTaskPushLatencyTimer().takeSnapshot();
        // Histogram 的 percentile 桶里 P95 应 ≤ 10s SLO
        assertTrue(metrics.offlineTaskPushLatencyTimer().count() == 100L);
        assertTrue(snapshot.max(java.util.concurrent.TimeUnit.SECONDS) <= 10.5,
                "P95 SLO 是 10s,本组样本 max=" + snapshot.max(java.util.concurrent.TimeUnit.SECONDS) + "s 应通过");
    }

    @Test
    void offlineTaskPushLatency_isRegisteredInMeterRegistry() {
        // 确保指标按 plan.md 要求的命名暴露在 Prometheus 端点
        var meter = registry.find("offline_task_push_latency_seconds").timer();
        assertNotNull(meter, "offline_task_push_latency_seconds 必须注册到 MeterRegistry");
    }

    @Test
    void existingCounters_notAffectedByNewTimer() {
        metrics.recordConnected();
        metrics.recordDisconnected();
        metrics.recordReplayTriggered();
        metrics.recordReconcileOnlineFixed();
        metrics.recordReconcileOfflineFixed();

        assertEquals(1.0, metrics.connectedCounter().count());
        assertEquals(1.0, metrics.disconnectedCounter().count());
        assertEquals(1.0, metrics.replayTriggeredCounter().count());
        assertEquals(1.0, metrics.reconcileOnlineFixedCounter().count());
        assertEquals(1.0, metrics.reconcileOfflineFixedCounter().count());
    }

    @Test
    void reconcileTimer_stillUsable() {
        var sample = metrics.startReconcile();
        try {
            Thread.sleep(Duration.ofMillis(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        metrics.recordReconcileTime(sample);
        // 不断言具体耗时,但 Timer 应当至少捕获了 1 个样本
        assertTrue(registry.find("dota_lifecycle_reconcile_seconds").timer().count() >= 1);
    }
}

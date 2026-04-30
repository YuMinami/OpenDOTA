package com.opendota.task.scope;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ScopeMetrics 单元测试。
 */
class ScopeMetricsTest {

    private SimpleMeterRegistry registry;
    private ScopeMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new ScopeMetrics(registry);
    }

    @Test
    void recordScan_incrementsCounter() {
        metrics.recordScan();
        metrics.recordScan();

        assertEquals(2, metrics.scanCounter().count());
    }

    @Test
    void recordNewcomer_incrementsByAmount() {
        metrics.recordNewcomer(5);
        metrics.recordNewcomer(3);

        assertEquals(8, metrics.newcomerCounter().count());
    }

    @Test
    void recordError_incrementsCounter() {
        metrics.recordError();

        assertEquals(1, metrics.errorCounter().count());
    }

    @Test
    void scanTimer_recordsLatency() throws InterruptedException {
        var sample = metrics.startScan();
        Thread.sleep(10);
        metrics.recordScanDuration(sample);

        assertEquals(1, registry.find("dota_scope_scan_duration_seconds").timer().count());
        assertTrue(registry.find("dota_scope_scan_duration_seconds").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) > 0);
    }
}

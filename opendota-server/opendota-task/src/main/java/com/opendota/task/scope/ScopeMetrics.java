package com.opendota.task.scope;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * DynamicTargetScopeWorker Prometheus 指标(架构 §4.4.4.1)。
 *
 * <p>所有指标前缀 {@code dota_scope_},Grafana 看板可直接引用。
 */
@Component
public class ScopeMetrics {

    private final Counter scanCounter;
    private final Counter newcomerCounter;
    private final Counter errorCounter;
    private final Timer scanTimer;

    public ScopeMetrics(MeterRegistry registry) {
        this.scanCounter = Counter.builder("dota_scope_scan_total")
                .description("DynamicTargetScopeWorker 扫描次数")
                .register(registry);
        this.newcomerCounter = Counter.builder("dota_scope_newcomer_total")
                .description("新纳入的 VIN 数量")
                .register(registry);
        this.errorCounter = Counter.builder("dota_scope_error_total")
                .description("扫描处理异常次数")
                .register(registry);
        this.scanTimer = Timer.builder("dota_scope_scan_duration_seconds")
                .description("单次扫描耗时")
                .register(registry);
    }

    public void recordScan() { scanCounter.increment(); }
    public void recordNewcomer(long count) { newcomerCounter.increment(count); }
    public void recordError() { errorCounter.increment(); }
    public Timer.Sample startScan() { return Timer.start(); }
    public void recordScanDuration(Timer.Sample sample) { sample.stop(scanTimer); }

    // --- 测试可见 ---
    public Counter scanCounter() { return scanCounter; }
    public Counter newcomerCounter() { return newcomerCounter; }
    public Counter errorCounter() { return errorCounter; }
}

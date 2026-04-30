package com.opendota.task.lifecycle;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * 车辆生命周期事件 Prometheus 指标(架构 §4.5-§4.6)。
 *
 * <p>指标前缀 {@code dota_lifecycle_},供 Grafana 车辆在线看板使用。
 */
@Component
public class LifecycleMetrics {

    private final Counter connectedCounter;
    private final Counter disconnectedCounter;
    private final Counter replayTriggeredCounter;
    private final Counter reconcileOnlineFixedCounter;
    private final Counter reconcileOfflineFixedCounter;
    private final Timer reconcileTimer;

    public LifecycleMetrics(MeterRegistry registry) {
        this.connectedCounter = Counter.builder("dota_lifecycle_connected_total")
                .description("车辆上线事件数")
                .register(registry);
        this.disconnectedCounter = Counter.builder("dota_lifecycle_disconnected_total")
                .description("车辆离线事件数")
                .register(registry);
        this.replayTriggeredCounter = Counter.builder("dota_lifecycle_replay_triggered_total")
                .description("上线触发 pending_online 回放数")
                .register(registry);
        this.reconcileOnlineFixedCounter = Counter.builder("dota_lifecycle_reconcile_online_fixed_total")
                .description("对账修正:EMQX 在线但 PG 离线,已修正为在线")
                .register(registry);
        this.reconcileOfflineFixedCounter = Counter.builder("dota_lifecycle_reconcile_offline_fixed_total")
                .description("对账修正:EMQX 离线但 PG 在线,已修正为离线")
                .register(registry);
        this.reconcileTimer = Timer.builder("dota_lifecycle_reconcile_seconds")
                .description("对账作业耗时")
                .register(registry);
    }

    public void recordConnected() { connectedCounter.increment(); }
    public void recordDisconnected() { disconnectedCounter.increment(); }
    public void recordReplayTriggered() { replayTriggeredCounter.increment(); }
    public void recordReconcileOnlineFixed() { reconcileOnlineFixedCounter.increment(); }
    public void recordReconcileOfflineFixed() { reconcileOfflineFixedCounter.increment(); }
    public Timer.Sample startReconcile() { return Timer.start(); }
    public void recordReconcileTime(Timer.Sample sample) { sample.stop(reconcileTimer); }

    // --- 测试可见 ---
    public Counter connectedCounter() { return connectedCounter; }
    public Counter disconnectedCounter() { return disconnectedCounter; }
    public Counter replayTriggeredCounter() { return replayTriggeredCounter; }
    public Counter reconcileOnlineFixedCounter() { return reconcileOnlineFixedCounter; }
    public Counter reconcileOfflineFixedCounter() { return reconcileOfflineFixedCounter; }
}

package com.opendota.task.lifecycle;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 车辆生命周期事件 Prometheus 指标(架构 §4.5-§4.6)。
 *
 * <p>指标前缀 {@code dota_lifecycle_},供 Grafana 车辆在线看板使用。
 *
 * <p><strong>Phase 4 DoD #3</strong>: {@code offline_task_push_latency_seconds} 是
 * "离线任务从入队 pending_online 到首次成功回放分发"的端到端延迟,要求 P95 < 10s
 * (100 车模拟上线场景),由 {@link PendingTaskReplayService#replayPendingTasks(String)}
 * 在每条 dispatch_record 回放时按 {@code now() - pending_online_at} 的差值打点。
 */
@Component
public class LifecycleMetrics {

    private final Counter connectedCounter;
    private final Counter disconnectedCounter;
    private final Counter replayTriggeredCounter;
    private final Counter reconcileOnlineFixedCounter;
    private final Counter reconcileOfflineFixedCounter;
    private final Timer reconcileTimer;
    private final Timer offlineTaskPushLatency;

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
        this.offlineTaskPushLatency = Timer.builder("offline_task_push_latency_seconds")
                .description("离线任务从 pending_online 入队到首次回放分发的端到端延迟(Phase 4 DoD,P95<10s)")
                // 直方图桶覆盖 100ms..30s,P95 SLO 在 10s 处
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(30))
                .register(registry);
    }

    public void recordConnected() { connectedCounter.increment(); }
    public void recordDisconnected() { disconnectedCounter.increment(); }
    public void recordReplayTriggered() { replayTriggeredCounter.increment(); }
    public void recordReconcileOnlineFixed() { reconcileOnlineFixedCounter.increment(); }
    public void recordReconcileOfflineFixed() { reconcileOfflineFixedCounter.increment(); }
    public Timer.Sample startReconcile() { return Timer.start(); }
    public void recordReconcileTime(Timer.Sample sample) { sample.stop(reconcileTimer); }

    /**
     * 记录一次离线任务推送延迟样本。
     *
     * @param latencyMillis 从 {@code pending_online_at} 到当前的毫秒数;
     *                      若基准时间戳缺失或 ≤ 0 则不打点(避免污染 P95)。
     */
    public void recordOfflineTaskPushLatency(long latencyMillis) {
        if (latencyMillis <= 0) {
            return;
        }
        offlineTaskPushLatency.record(Duration.ofMillis(latencyMillis));
    }

    // --- 测试可见 ---
    public Counter connectedCounter() { return connectedCounter; }
    public Counter disconnectedCounter() { return disconnectedCounter; }
    public Counter replayTriggeredCounter() { return replayTriggeredCounter; }
    public Counter reconcileOnlineFixedCounter() { return reconcileOnlineFixedCounter; }
    public Counter reconcileOfflineFixedCounter() { return reconcileOfflineFixedCounter; }
    public Timer offlineTaskPushLatencyTimer() { return offlineTaskPushLatency; }
}

package com.opendota.task.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox 投递指标(架构 §3.3.1.1 指标 5)。
 *
 * <p>指标清单:
 * <ul>
 *   <li>{@code outbox_relay_sent_total} — 成功投递计数</li>
 *   <li>{@code outbox_relay_failed_total} — 投递失败计数</li>
 *   <li>{@code outbox_relay_batch_size} — 每批拉取条数分布</li>
 *   <li>{@code outbox_relay_latency_seconds} — 单批 drain 耗时</li>
 *   <li>{@code outbox_pending_total{status="new|failed|sent_recent"}} —
 *       <strong>Phase 4 DoD #4</strong>: outbox 各状态深度,由
 *       {@link OutboxPendingGaugeRefresher} 周期刷新</li>
 * </ul>
 *
 * <p>告警规则(运维侧配置 deploy/prometheus/opendota-alerts.yml):
 * <ul>
 *   <li>{@code outbox_pending_total{status='failed'} > 10000} → P1</li>
 *   <li>{@code outbox_relay_latency_seconds P99 > 5} → P1</li>
 * </ul>
 */
@Component
public class OutboxMetrics {

    private final Counter sentCounter;
    private final Counter failedCounter;
    private final Timer drainTimer;

    private final AtomicLong pendingNewGauge = new AtomicLong(0);
    private final AtomicLong pendingFailedGauge = new AtomicLong(0);
    private final AtomicLong sentRecentGauge = new AtomicLong(0);

    public OutboxMetrics(MeterRegistry registry) {
        this.sentCounter = Counter.builder("outbox_relay_sent_total")
                .description("Outbox 成功投递到 Kafka 的事件总数")
                .register(registry);
        this.failedCounter = Counter.builder("outbox_relay_failed_total")
                .description("Outbox 投递失败的事件总数")
                .register(registry);
        this.drainTimer = Timer.builder("outbox_relay_latency_seconds")
                .description("单批 drain 耗时")
                .register(registry);

        // Phase 4 DoD #4: outbox_pending_total gauge,按 status 标签拆分
        Gauge.builder("outbox_pending_total", pendingNewGauge, AtomicLong::doubleValue)
                .description("Outbox 待投递事件数(status='new')")
                .tag("status", "new")
                .register(registry);
        Gauge.builder("outbox_pending_total", pendingFailedGauge, AtomicLong::doubleValue)
                .description("Outbox 投递失败重试中事件数(status='failed')")
                .tag("status", "failed")
                .register(registry);
        Gauge.builder("outbox_pending_total", sentRecentGauge, AtomicLong::doubleValue)
                .description("Outbox 最近 5 分钟成功投递事件数(status='sent' && sent_at > now()-5min)")
                .tag("status", "sent_recent")
                .register(registry);
    }

    public void recordSent(int count) {
        sentCounter.increment(count);
    }

    public void recordFailed() {
        failedCounter.increment();
    }

    public Timer.Sample startDrainTimer() {
        return Timer.start();
    }

    public void stopDrainTimer(Timer.Sample sample) {
        sample.stop(drainTimer);
    }

    /**
     * 刷新 outbox_pending_total gauge(由 {@link OutboxPendingGaugeRefresher} 调用)。
     */
    public void updatePendingDepth(long pendingNew, long pendingFailed, long sentRecent) {
        pendingNewGauge.set(pendingNew);
        pendingFailedGauge.set(pendingFailed);
        sentRecentGauge.set(sentRecent);
    }

    public Counter sentCounter() { return sentCounter; }
    public Counter failedCounter() { return failedCounter; }
    public long pendingNew() { return pendingNewGauge.get(); }
    public long pendingFailed() { return pendingFailedGauge.get(); }
    public long sentRecent() { return sentRecentGauge.get(); }
}

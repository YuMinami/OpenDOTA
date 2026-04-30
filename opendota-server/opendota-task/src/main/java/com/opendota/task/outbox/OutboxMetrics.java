package com.opendota.task.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Outbox 投递指标(架构 §3.3.1.1 指标 5)。
 *
 * <p>指标清单:
 * <ul>
 *   <li>{@code outbox_relay_sent_total} — 成功投递计数</li>
 *   <li>{@code outbox_relay_failed_total} — 投递失败计数</li>
 *   <li>{@code outbox_relay_batch_size} — 每批拉取条数分布</li>
 *   <li>{@code outbox_relay_latency_seconds} — 单批 drain 耗时</li>
 * </ul>
 *
 * <p>告警规则(运维侧配置):
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

    public Counter sentCounter() { return sentCounter; }
    public Counter failedCounter() { return failedCounter; }
}

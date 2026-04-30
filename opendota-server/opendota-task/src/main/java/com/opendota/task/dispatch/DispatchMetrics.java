package com.opendota.task.dispatch;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * 分发调度器 Prometheus 指标(架构 §4.4)。
 *
 * <p>所有指标前缀 {@code dota_dispatch_},Grafana 看板可直接引用。
 */
@Component
public class DispatchMetrics {

    private final Counter totalCounter;
    private final Counter mqttPublishedCounter;
    private final Counter rateLimitedCounter;
    private final Counter pendingOnlineCounter;
    private final Counter dlqCounter;
    private final Timer latencyTimer;

    public DispatchMetrics(MeterRegistry registry) {
        this.totalCounter = Counter.builder("dota_dispatch_total")
                .description("分发命令总数")
                .register(registry);
        this.mqttPublishedCounter = Counter.builder("dota_dispatch_mqtt_published_total")
                .description("MQTT publish 成功数")
                .register(registry);
        this.rateLimitedCounter = Counter.builder("dota_dispatch_rate_limited_total")
                .description("被限流器拒绝的分发数")
                .register(registry);
        this.pendingOnlineCounter = Counter.builder("dota_dispatch_pending_online_total")
                .description("标记为 pending_online 的分发数(离线车)")
                .register(registry);
        this.dlqCounter = Counter.builder("dota_dispatch_dlq_total")
                .description("进入死信队列的分发数")
                .register(registry);
        this.latencyTimer = Timer.builder("dota_dispatch_latency_seconds")
                .description("分发处理延迟")
                .register(registry);
    }

    public void recordDispatched() { totalCounter.increment(); }
    public void recordMqttPublished() { mqttPublishedCounter.increment(); }
    public void recordRateLimited() { rateLimitedCounter.increment(); }
    public void recordPendingOnline() { pendingOnlineCounter.increment(); }
    public void recordDlq() { dlqCounter.increment(); }
    public Timer.Sample startLatency() { return Timer.start(); }
    public void recordLatency(Timer.Sample sample) { sample.stop(latencyTimer); }

    // --- 测试可见 ---
    public Counter totalCounter() { return totalCounter; }
    public Counter mqttPublishedCounter() { return mqttPublishedCounter; }
    public Counter rateLimitedCounter() { return rateLimitedCounter; }
    public Counter pendingOnlineCounter() { return pendingOnlineCounter; }
    public Counter dlqCounter() { return dlqCounter; }
}

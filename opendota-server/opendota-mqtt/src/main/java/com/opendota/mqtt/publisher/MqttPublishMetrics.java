package com.opendota.mqtt.publisher;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * MQTT 发布指标。
 */
public class MqttPublishMetrics {

    private final Counter publishTotal;
    private final Counter publishFailedTotal;
    private final Timer publishLatency;

    public MqttPublishMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "MeterRegistry 必填");
        this.publishTotal = registry.counter("dota_mqtt_publish_total");
        this.publishFailedTotal = registry.counter("dota_mqtt_publish_failed_total");
        this.publishLatency = registry.timer("dota_mqtt_publish_latency_seconds");
    }

    public void recordSuccess(long durationNanos) {
        publishTotal.increment();
        publishLatency.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordFailure(long durationNanos) {
        publishFailedTotal.increment();
        publishLatency.record(durationNanos, TimeUnit.NANOSECONDS);
    }
}

package com.opendota.task.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Kafka topic 名称配置。
 *
 * <p>从 {@code opendota.kafka.topic.*} 读取,避免硬编码 topic 名。
 */
@Component
@ConfigurationProperties(prefix = "opendota.kafka.topic")
public class KafkaTopicProperties {

    private String dispatchQueue = "task-dispatch-queue";
    private String vehicleLifecycle = "vehicle-lifecycle";
    private String dlq = "task-dispatch-dlq";

    public String getDispatchQueue() { return dispatchQueue; }
    public void setDispatchQueue(String dispatchQueue) { this.dispatchQueue = dispatchQueue; }

    public String getVehicleLifecycle() { return vehicleLifecycle; }
    public void setVehicleLifecycle(String vehicleLifecycle) { this.vehicleLifecycle = vehicleLifecycle; }

    public String getDlq() { return dlq; }
    public void setDlq(String dlq) { this.dlq = dlq; }
}

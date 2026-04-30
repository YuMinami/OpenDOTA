package com.opendota.task.outbox;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 事务性 Outbox 事件实体(架构 §3.3.1)。
 *
 * <p>与 outbox_event 表一一对应。任务创建时在同一 PG 事务内写入,
 * 由 {@link OutboxRelayWorker} 异步扫描投递到 Kafka。
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "aggregate_type", nullable = false, length = 32)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "kafka_topic", nullable = false, length = 128)
    private String kafkaTopic;

    @Column(name = "kafka_key", length = 128)
    private String kafkaKey;

    @Column(length = 16)
    private String status = "new";

    private Integer attempts = 0;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    protected OutboxEvent() {}

    public OutboxEvent(String tenantId, String aggregateType, String aggregateId,
                       String eventType, String payload, String kafkaTopic, String kafkaKey) {
        this.tenantId = tenantId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.kafkaTopic = kafkaTopic;
        this.kafkaKey = kafkaKey;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getKafkaTopic() { return kafkaTopic; }
    public String getKafkaKey() { return kafkaKey; }
    public String getStatus() { return status; }
    public Integer getAttempts() { return attempts; }
    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public String getLastError() { return lastError; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getSentAt() { return sentAt; }

    public void setStatus(String status) { this.status = status; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
    public void setNextRetryAt(LocalDateTime nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
}

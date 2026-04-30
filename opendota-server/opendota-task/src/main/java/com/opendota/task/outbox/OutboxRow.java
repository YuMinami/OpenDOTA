package com.opendota.task.outbox;

/**
 * Outbox 待投递行数据(从 FOR UPDATE SKIP LOCKED 查询投影)。
 */
public record OutboxRow(
        long id,
        String tenantId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        String kafkaTopic,
        String kafkaKey,
        int attempts
) {}

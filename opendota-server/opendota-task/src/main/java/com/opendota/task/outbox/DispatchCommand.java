package com.opendota.task.outbox;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Kafka 分发指令 payload(架构 §4.4.1)。
 *
 * <p>由 OutboxRelayWorker 投递到 task-dispatch-queue topic,
 * 由 {@code DispatchCommandListener} 消费并下发 MQTT schedule_set。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DispatchCommand(
        String taskId,
        String tenantId,
        String vin,
        String ecuScope,
        int priority,
        String scheduleType,
        String payloadType,
        String diagPayload,
        String payloadHash
) {}

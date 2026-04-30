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
        String payloadHash,
        /** 被 supersede 的旧任务 ID(协议 §12.6.3),非 null 时车端走 supersede 分支 */
        String supersedes
) {
    /**
     * 常规构造(无 supersedes)。
     */
    public DispatchCommand(String taskId, String tenantId, String vin, String ecuScope,
                           int priority, String scheduleType, String payloadType,
                           String diagPayload, String payloadHash) {
        this(taskId, tenantId, vin, ecuScope, priority, scheduleType, payloadType,
             diagPayload, payloadHash, null);
    }
}

package com.opendota.common.payload.channel;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 通道状态事件 payload。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChannelEventPayload(
        String channelId,
        String event,
        Integer status,
        String currentSession,
        String currentSecurityLevel,
        String currentTaskId,
        String reason,
        String msg) {
}

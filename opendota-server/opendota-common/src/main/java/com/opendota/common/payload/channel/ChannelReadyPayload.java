package com.opendota.common.payload.channel;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 诊断资源回到可重试状态的主动通知。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChannelReadyPayload(
        String resourceState,
        String completedTaskId,
        Long readyAt,
        String pendingChannelOpenMsgId) {
}

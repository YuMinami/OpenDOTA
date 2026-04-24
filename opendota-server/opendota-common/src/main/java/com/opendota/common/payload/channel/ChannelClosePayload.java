package com.opendota.common.payload.channel;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 通道关闭 payload。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChannelClosePayload(
        String channelId,
        Boolean resetSession) {
}

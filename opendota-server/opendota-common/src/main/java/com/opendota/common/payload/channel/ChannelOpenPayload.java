package com.opendota.common.payload.channel;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.opendota.common.payload.common.DoipConfig;
import com.opendota.common.payload.common.EcuScope;
import com.opendota.common.payload.common.Transport;

import java.util.List;

/**
 * 通道开启 payload。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChannelOpenPayload(
        String channelId,
        String ecuName,
        List<String> ecuScope,
        Transport transport,
        String txId,
        String rxId,
        DoipConfig doipConfig,
        Integer globalTimeoutMs,
        Boolean force,
        String preemptPolicy) {

    public ChannelOpenPayload {
        ecuScope = ecuScope == null ? null : EcuScope.normalize(ecuScope);
        transport = transport == null ? Transport.UDS_ON_CAN : transport;
    }
}

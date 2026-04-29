package com.opendota.common.payload.single;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单步诊断指令 payload。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SingleCmdPayload(
        String cmdId,
        String channelId,
        String ecuName,
        String type,
        String reqData,
        Integer timeoutMs) {

    public SingleCmdPayload {
        timeoutMs = timeoutMs == null ? 5000 : timeoutMs;
    }
}

package com.opendota.common.payload.single;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单步诊断结果 payload。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SingleRespPayload(
        String cmdId,
        String channelId,
        Integer status,
        String errorCode,
        String resData,
        Integer execDuration) {
}

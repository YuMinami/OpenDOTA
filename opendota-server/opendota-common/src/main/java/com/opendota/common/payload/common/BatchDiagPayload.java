package com.opendota.common.payload.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单 ECU 任务载荷(schema: BatchDiagPayload)。
 *
 * <p>Step 1.1 先放在 common 包,后续若任务域类型继续增多,再按 task/script 拆包。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchDiagPayload(
        String payloadType,
        String ecuName,
        List<String> ecuScope,
        Transport transport,
        String txId,
        String rxId,
        DoipConfig doipConfig,
        Integer strategy,
        List<Step> steps) {

    public BatchDiagPayload {
        payloadType = payloadType == null ? "batch" : payloadType;
        ecuScope = ecuScope == null ? null : EcuScope.normalize(ecuScope);
        transport = transport == null ? Transport.UDS_ON_CAN : transport;
        strategy = strategy == null ? 1 : strategy;
        steps = steps == null ? null : List.copyOf(steps);
    }
}

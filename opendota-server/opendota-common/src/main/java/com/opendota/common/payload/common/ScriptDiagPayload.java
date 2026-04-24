package com.opendota.common.payload.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 多 ECU 编排任务载荷(schema: ScriptDiagPayload)。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScriptDiagPayload(
        String payloadType,
        String scriptId,
        String executionMode,
        Integer globalTimeoutMs,
        Integer priority,
        List<EcuBlock> ecus) {

    public ScriptDiagPayload {
        payloadType = payloadType == null ? "script" : payloadType;
        priority = priority == null ? 5 : priority;
        ecus = ecus == null ? null : List.copyOf(ecus);
    }
}

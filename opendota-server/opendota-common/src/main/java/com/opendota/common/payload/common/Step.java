package com.opendota.common.payload.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 批量 / 脚本任务中的单个步骤。
 *
 * <p>宏参数先统一收敛到 {@code macroParams},与 schema 保持一致。
 * 后续若协议重新收敛为显式字段,再做定向拆分。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Step(
        Integer seqId,
        String type,
        String data,
        Integer timeoutMs,
        JsonNode macroParams) {

    public Step {
        timeoutMs = timeoutMs == null ? 3000 : timeoutMs;
    }
}

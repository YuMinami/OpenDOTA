package com.opendota.common.payload.script;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.opendota.common.payload.batch.BatchStepResult;

import java.util.List;

/**
 * 多 ECU 脚本中单个 ECU 的执行结果(协议 §9.3 script_resp.ecuResults[])。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScriptEcuResult(
        String ecuName,
        Integer overallStatus,
        Long ecuDuration,
        List<BatchStepResult> results) {
}

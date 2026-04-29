package com.opendota.common.payload.batch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 批量诊断响应 payload(协议 §6 batch_resp)。
 *
 * <p>车端执行完 batch_cmd 后,将所有步骤结果汇总为一条 batch_resp 返回。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record BatchRespPayload(
        String taskId,
        Integer overallStatus,
        Long taskDuration,
        List<BatchStepResult> results) {
}

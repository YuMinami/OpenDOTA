package com.opendota.common.payload.batch;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 批量诊断单步结果(协议 §6 batch_resp.results[])。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchStepResult(
        Integer seqId,
        Integer status,
        String errorCode,
        String resData,
        String msg) {
}

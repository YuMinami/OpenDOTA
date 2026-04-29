package com.opendota.common.payload.script;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 多 ECU 脚本响应载荷(协议 §9.3 script_resp)。
 *
 * <p>车端执行完 script_cmd 后,将所有 ECU 结果汇总为一条 script_resp 返回。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScriptRespPayload(
        String scriptId,
        Integer overallStatus,
        Long scriptDuration,
        List<ScriptEcuResult> ecuResults) {
}

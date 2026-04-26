package com.opendota.odx.service;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 服务目录视图(协议 §13.4.2)。
 *
 * <p>按 {@code category} 分组的诊断服务树,前端用作 ECU 选中后的目录展示。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EcuServiceCatalog(
        Long ecuId,
        String ecuName,
        String txId,
        String rxId,
        String protocol,
        List<Category> categories) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Category(String category, List<ServiceItem> services) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ServiceItem(
            Long id,
            String displayName,
            String serviceCode,
            String subFunction,
            String requestRawHex,
            String responseIdHex,
            String macroType,
            String requiredSession,
            String requiredSecLevel,
            Boolean requiresSecurity,
            Boolean safetyCritical) {
    }
}

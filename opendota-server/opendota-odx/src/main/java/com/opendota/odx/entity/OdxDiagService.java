package com.opendota.odx.entity;

/**
 * 诊断服务定义(对应 SQL 表 {@code odx_diag_service},协议 §13.3.2 表 3)。
 *
 * <p>{@link #macroType()} 由导入时自动判定(协议 §13.3.3),前端无需运行时判断。
 */
public record OdxDiagService(
        Long id,
        Long ecuId,
        String serviceCode,
        String subFunction,
        String serviceName,
        String displayName,
        String description,
        String category,
        String requestRawHex,
        String responseIdHex,
        Boolean requiresSecurity,
        String requiredSecLevel,
        String requiredSession,
        String macroType,
        Boolean isEnabled,
        Boolean safetyCritical) {
}

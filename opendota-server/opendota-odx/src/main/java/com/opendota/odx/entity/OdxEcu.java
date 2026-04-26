package com.opendota.odx.entity;

import java.util.List;

/**
 * ECU 定义(对应 SQL 表 {@code odx_ecu},协议 §13.3.2 表 2)。
 *
 * <p>{@link #gatewayChain()} 来自 V3 迁移补字段,用于 EcuScopeResolver 自动推导
 * 完整 ecuScope(REST §4.1.1)。空列表表示 ECU 直连 OBD,无网关依赖。
 */
public record OdxEcu(
        Long id,
        Long modelId,
        String ecuCode,
        String ecuName,
        String txId,
        String rxId,
        String protocol,
        String secAlgoRef,
        List<String> gatewayChain) {

    public OdxEcu {
        gatewayChain = gatewayChain == null ? List.of() : List.copyOf(gatewayChain);
    }
}

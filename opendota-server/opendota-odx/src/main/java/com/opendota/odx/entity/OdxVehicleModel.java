package com.opendota.odx.entity;

/**
 * 车型基础信息(对应 SQL 表 {@code odx_vehicle_model},协议 §13.3.2 表 1)。
 *
 * <p>每个租户(tenant)可拥有多个车型,通过 {@code (tenantId, modelCode)} 唯一确定。
 */
public record OdxVehicleModel(
        Long id,
        String tenantId,
        String modelCode,
        String modelName,
        String odxVersion) {
}

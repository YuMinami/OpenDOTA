package com.opendota.odx.entity;

import java.math.BigDecimal;

/**
 * 参数编解码规则(对应 SQL 表 {@code odx_param_codec},协议 §13.3.2 表 4)。
 *
 * <p>{@link #enumMappingJson()} 是 JSONB 列原文。考虑到 Step 2.2 仅做读取展示,
 * 不在实体里反序列化为 {@code Map<String,String>},而由上层按需解析,避免引入
 * 不必要的 Jackson 边界。
 */
public record OdxParamCodec(
        Long id,
        Long serviceId,
        String paramName,
        String displayName,
        Integer byteOffset,
        Integer bitOffset,
        Integer bitLength,
        String dataType,
        String formula,
        String unit,
        String enumMappingJson,
        BigDecimal minValue,
        BigDecimal maxValue) {
}

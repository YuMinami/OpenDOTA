package com.opendota.odx.service;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * ECU 作用域推导响应(REST §4.1.1)。
 *
 * <p>{@code ecuScope} 已按字典序规范化(协议 §10.2.3),前端可直接回填 channel_open 表单。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EcuScopeResolution(
        String vin,
        String modelCode,
        String requested,
        List<String> ecuScope,
        String reason) {
}

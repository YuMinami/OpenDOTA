package com.opendota.odx.service;

/**
 * ODX 资源不存在(车型、ECU、服务)运行时异常。
 *
 * <p>用于跨模块解耦:opendota-odx 不便依赖 opendota-diag 的 {@code BusinessException},
 * 改由 {@code GlobalExceptionHandler} 统一映射为 REST 错误码 {@code 40401}。
 */
public class OdxResourceNotFoundException extends RuntimeException {

    public OdxResourceNotFoundException(String message) {
        super(message);
    }
}

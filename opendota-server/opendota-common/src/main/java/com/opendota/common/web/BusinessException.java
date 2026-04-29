package com.opendota.common.web;

import java.util.Objects;

/**
 * 业务异常。统一由 GlobalExceptionHandler 映射为响应信封。
 */
public class BusinessException extends RuntimeException {

    private final ApiError error;

    public BusinessException(ApiError error) {
        super(Objects.requireNonNull(error, "error 必填").defaultMessage());
        this.error = error;
    }

    public BusinessException(ApiError error, String message) {
        super(hasText(message) ? message : Objects.requireNonNull(error, "error 必填").defaultMessage());
        this.error = Objects.requireNonNull(error, "error 必填");
    }

    public BusinessException(ApiError error, String message, Throwable cause) {
        super(hasText(message) ? message : Objects.requireNonNull(error, "error 必填").defaultMessage(), cause);
        this.error = Objects.requireNonNull(error, "error 必填");
    }

    public ApiError error() {
        return error;
    }

    public int code() {
        return error.code();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

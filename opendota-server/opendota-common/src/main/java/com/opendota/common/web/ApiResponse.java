package com.opendota.common.web;

import java.util.Objects;

/**
 * 统一 REST 响应信封({@code code / msg / data})。
 */
public record ApiResponse<T>(int code, String msg, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ApiError.SUCCESS.code(), ApiError.SUCCESS.defaultMessage(), data);
    }

    public static <T> ApiResponse<T> error(ApiError error) {
        Objects.requireNonNull(error, "error 必填");
        return new ApiResponse<>(error.code(), error.defaultMessage(), null);
    }

    public static <T> ApiResponse<T> error(ApiError error, String msg) {
        Objects.requireNonNull(error, "error 必填");
        return new ApiResponse<>(error.code(), hasText(msg) ? msg : error.defaultMessage(), null);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

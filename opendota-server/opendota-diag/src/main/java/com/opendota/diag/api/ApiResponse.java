package com.opendota.diag.api;

/**
 * 统一 REST 响应包装({@code code / msg / data})。CLAUDE.md 约定,SpringDoc 也按这个结构生成 schema。
 *
 * <p>成功:{@code ApiResponse.ok(data)} → {@code {code:0, msg:"", data:{...}}}
 * <br>失败:{@code ApiResponse.error(40305, "...")} → {@code {code:40305, msg:"...", data:null}}
 */
public record ApiResponse<T>(int code, String msg, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "", data);
    }

    public static <T> ApiResponse<T> error(int code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }
}

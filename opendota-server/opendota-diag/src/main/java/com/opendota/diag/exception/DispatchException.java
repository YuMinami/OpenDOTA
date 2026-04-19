package com.opendota.diag.exception;

/**
 * 诊断下发链路业务异常。被 {@code GlobalExceptionHandler} 捕获并映射为
 * {@code {code, msg, data:null}} 响应。
 */
public class DispatchException extends RuntimeException {

    private final int code;

    public DispatchException(int code, String message) {
        super(message);
        this.code = code;
    }

    public DispatchException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int code() {
        return code;
    }
}

package com.opendota.security.auth;

/**
 * 认证业务异常。
 */
public class AuthException extends RuntimeException {

    private final AuthError error;

    public AuthException(AuthError error) {
        super(error.apiError().defaultMessage());
        this.error = error;
    }

    public AuthError getError() {
        return error;
    }
}

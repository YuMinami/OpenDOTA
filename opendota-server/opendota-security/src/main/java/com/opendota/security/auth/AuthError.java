package com.opendota.security.auth;

import com.opendota.common.web.ApiError;

/**
 * 认证错误码映射。
 */
enum AuthError {
    INVALID_CREDENTIALS(ApiError.E40101),
    TOKEN_EXPIRED(ApiError.E40102),
    TOKEN_REVOKED(ApiError.E40103);

    private final ApiError apiError;

    AuthError(ApiError apiError) {
        this.apiError = apiError;
    }

    public ApiError apiError() {
        return apiError;
    }
}

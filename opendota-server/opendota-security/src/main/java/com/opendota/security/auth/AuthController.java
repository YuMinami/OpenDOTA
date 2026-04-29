package com.opendota.security.auth;

import com.opendota.common.envelope.OperatorRole;
import com.opendota.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 认证 REST 端点(REST API Spec §2)。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录:邮箱 + 密码 + 租户 → JWT。
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(request.email(), request.password(), request.tenant());
        return ApiResponse.ok(new LoginResponse(
                result.token(),
                result.expiresAt(),
                new OperatorInfoResponse(
                        result.operator().id(),
                        result.operator().displayName(),
                        result.operator().email(),
                        result.operator().role(),
                        result.operator().tenantId()
                )
        ));
    }

    /**
     * 刷新 token:Authorization header 中的旧 token → 新 JWT。
     */
    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(HttpServletRequest request) {
        String oldToken = extractBearerToken(request);
        if (oldToken == null) {
            throw new AuthException(AuthError.INVALID_CREDENTIALS);
        }
        AuthService.LoginResult result = authService.refreshToken(oldToken);
        return ApiResponse.ok(new LoginResponse(
                result.token(),
                result.expiresAt(),
                new OperatorInfoResponse(
                        result.operator().id(),
                        result.operator().displayName(),
                        result.operator().email(),
                        result.operator().role(),
                        result.operator().tenantId()
                )
        ));
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password,
            @NotBlank String tenant
    ) {}

    public record LoginResponse(String token, Instant expiresAt, OperatorInfoResponse operator) {}

    public record OperatorInfoResponse(String id, String displayName, String email, OperatorRole role, String tenantId) {}
}

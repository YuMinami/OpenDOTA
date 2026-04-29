package com.opendota.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 安全模块配置属性。
 *
 * @param jwt           JWT 相关配置
 * @param permitAllPaths 无需认证的路径列表
 */
@ConfigurationProperties(prefix = "opendota.security")
public record SecurityProperties(
        JwtProperties jwt,
        List<String> permitAllPaths
) {
    /**
     * JWT 配置。
     *
     * @param secret        Base64 编码的 HMAC 密钥(>=256 bit)
     * @param expirySeconds token 有效期(秒),默认 28800(8h)
     * @param issuer        签发者标识
     */
    public record JwtProperties(
            String secret,
            long expirySeconds,
            String issuer
    ) {}
}

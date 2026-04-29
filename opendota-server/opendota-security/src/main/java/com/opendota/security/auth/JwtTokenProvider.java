package com.opendota.security.auth;

import com.opendota.common.envelope.OperatorRole;
import com.opendota.security.config.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * JWT token 创建、验证、撤销。
 * 使用 JJWT 0.12.6 HMAC-SHA256 签名。
 * 撤销通过 Redis 黑名单实现:{@code opendota:jwt:blocklist:{jti}}。
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);
    private static final String BLOCKLIST_PREFIX = "opendota:jwt:blocklist:";

    private final SecretKey signingKey;
    private final long expirySeconds;
    private final String issuer;
    private final StringRedisTemplate redisTemplate;

    public JwtTokenProvider(SecurityProperties properties, StringRedisTemplate redisTemplate) {
        byte[] keyBytes = Base64.getDecoder().decode(properties.jwt().secret());
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirySeconds = properties.jwt().expirySeconds();
        this.issuer = properties.jwt().issuer();
        this.redisTemplate = redisTemplate;
    }

    /**
     * 创建签名 JWT。
     *
     * @param operatorId 操作员 ID
     * @param tenantId   租户 ID
     * @param role       角色
     * @return JWT token 字符串
     */
    public String createToken(String operatorId, String tenantId, OperatorRole role) {
        Instant now = Instant.now();
        String jti = UUID.randomUUID().toString();

        return Jwts.builder()
                .id(jti)
                .subject(operatorId)
                .issuer(issuer)
                .claim("tenantId", tenantId)
                .claim("role", role.wireName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirySeconds)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * 验证 token 有效性:签名 + 过期 + 黑名单。
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = parseToken(token);
            return !isRevoked(claims.getId());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 验证失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 解析并返回所有 claims。调用前应先 validateToken。
     *
     * @throws ExpiredJwtException token 已过期
     * @throws JwtException        签名无效或其他解析错误
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 将 jti 加入 Redis 黑名单,TTL 等于 token 剩余有效期。
     */
    public void revokeToken(String jti, Duration ttl) {
        if (ttl.isPositive()) {
            redisTemplate.opsForValue().set(BLOCKLIST_PREFIX + jti, "1", ttl);
            log.debug("JWT 已撤销 jti={} ttl={}", jti, ttl);
        }
    }

    /**
     * 检查 jti 是否在黑名单中。
     */
    public boolean isRevoked(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLOCKLIST_PREFIX + jti));
    }

    /**
     * 获取 token 剩余有效期。
     */
    public Duration remainingTtl(Claims claims) {
        Instant expiry = claims.getExpiration().toInstant();
        Duration remaining = Duration.between(Instant.now(), expiry);
        return remaining.isPositive() ? remaining : Duration.ZERO;
    }

    public long getExpirySeconds() {
        return expirySeconds;
    }
}

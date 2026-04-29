package com.opendota.security.auth;

import com.opendota.common.envelope.OperatorRole;
import com.opendota.security.config.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JwtTokenProviderTest {

    private JwtTokenProvider provider;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        String secret = Base64.getEncoder().encodeToString("test-secret-key-for-jwt-32bytes!".getBytes());
        SecurityProperties props = new SecurityProperties(
                new SecurityProperties.JwtProperties(secret, 3600, "opendota"),
                java.util.List.of()
        );
        provider = new JwtTokenProvider(props, redisTemplate);
    }

    @Test
    void createToken_containsExpectedClaims() {
        String token = provider.createToken("op-001", "tenant-a", OperatorRole.ENGINEER);
        assertNotNull(token);

        Claims claims = provider.parseToken(token);
        assertEquals("op-001", claims.getSubject());
        assertEquals("tenant-a", claims.get("tenantId", String.class));
        assertEquals("engineer", claims.get("role", String.class));
        assertEquals("opendota", claims.getIssuer());
        assertNotNull(claims.getId());
        assertNotNull(claims.getExpiration());
    }

    @Test
    void validateToken_validToken_returnsTrue() {
        String token = provider.createToken("op-001", "tenant-a", OperatorRole.ENGINEER);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        assertTrue(provider.validateToken(token));
    }

    @Test
    void validateToken_revokedToken_returnsFalse() {
        String token = provider.createToken("op-001", "tenant-a", OperatorRole.ENGINEER);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        assertFalse(provider.validateToken(token));
    }

    @Test
    void validateToken_tamperedToken_returnsFalse() {
        String token = provider.createToken("op-001", "tenant-a", OperatorRole.ENGINEER);
        assertFalse(provider.validateToken(token + "tampered"));
    }

    @Test
    void revokeToken_storesInRedis() {
        provider.revokeToken("test-jti", Duration.ofMinutes(30));
        verify(valueOps).set(eq("opendota:jwt:blocklist:test-jti"), eq("1"), eq(Duration.ofMinutes(30)));
    }

    @Test
    void revokeToken_zeroTtl_doesNotStore() {
        provider.revokeToken("test-jti", Duration.ZERO);
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void remainingTtl_returnsPositiveDuration() {
        String token = provider.createToken("op-001", "tenant-a", OperatorRole.ENGINEER);
        Claims claims = provider.parseToken(token);
        Duration remaining = provider.remainingTtl(claims);
        assertTrue(remaining.isPositive());
        assertTrue(remaining.getSeconds() <= 3600);
    }
}

package com.opendota.security.auth;

import com.opendota.common.envelope.OperatorRole;
import com.opendota.security.model.*;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private AuthService authService;
    private OperatorRepository operatorRepo;
    private OperatorRoleRepository operatorRoleRepo;
    private RoleRepository roleRepo;
    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        operatorRepo = mock(OperatorRepository.class);
        operatorRoleRepo = mock(OperatorRoleRepository.class);
        roleRepo = mock(RoleRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        jwtTokenProvider = mock(JwtTokenProvider.class);
        authService = new AuthService(operatorRepo, operatorRoleRepo, roleRepo, passwordEncoder, jwtTokenProvider);
    }

    @Test
    void login_success() {
        OperatorEntity op = new OperatorEntity("op-001", "tenant-a", "test@example.com", OperatorStatus.ACTIVE);
        op.setPasswordHash(passwordEncoder.encode("password123"));
        when(operatorRepo.findByTenantIdAndEmail("tenant-a", "test@example.com")).thenReturn(Optional.of(op));

        RoleEntity role = new RoleEntity("engineer", "工程师");
        roleRepo.findById(2); // stub
        when(operatorRoleRepo.findByOperatorId("op-001")).thenReturn(
                List.of(new OperatorRoleEntity("op-001", 2, "system")));
        when(roleRepo.findById(2)).thenReturn(Optional.of(role));
        when(jwtTokenProvider.createToken("op-001", "tenant-a", OperatorRole.ENGINEER)).thenReturn("jwt-token");
        when(jwtTokenProvider.getExpirySeconds()).thenReturn(3600L);

        AuthService.LoginResult result = authService.login("test@example.com", "password123", "tenant-a");

        assertEquals("jwt-token", result.token());
        assertEquals("op-001", result.operator().id());
        assertEquals(OperatorRole.ENGINEER, result.operator().role());
        verify(operatorRepo).save(op);
    }

    @Test
    void login_invalidCredentials_throws() {
        when(operatorRepo.findByTenantIdAndEmail("tenant-a", "bad@example.com")).thenReturn(Optional.empty());

        AuthException ex = assertThrows(AuthException.class,
                () -> authService.login("bad@example.com", "password", "tenant-a"));
        assertEquals(AuthError.INVALID_CREDENTIALS, ex.getError());
    }

    @Test
    void login_wrongPassword_throws() {
        OperatorEntity op = new OperatorEntity("op-001", "tenant-a", "test@example.com", OperatorStatus.ACTIVE);
        op.setPasswordHash(passwordEncoder.encode("correct-password"));
        when(operatorRepo.findByTenantIdAndEmail("tenant-a", "test@example.com")).thenReturn(Optional.of(op));

        AuthException ex = assertThrows(AuthException.class,
                () -> authService.login("test@example.com", "wrong-password", "tenant-a"));
        assertEquals(AuthError.INVALID_CREDENTIALS, ex.getError());
    }

    @Test
    void login_disabledAccount_throws() {
        OperatorEntity op = new OperatorEntity("op-001", "tenant-a", "test@example.com", OperatorStatus.DISABLED);
        op.setPasswordHash(passwordEncoder.encode("password123"));
        when(operatorRepo.findByTenantIdAndEmail("tenant-a", "test@example.com")).thenReturn(Optional.of(op));

        AuthException ex = assertThrows(AuthException.class,
                () -> authService.login("test@example.com", "password123", "tenant-a"));
        assertEquals(AuthError.TOKEN_REVOKED, ex.getError());
    }

    @Test
    void login_noRoles_defaultsToViewer() {
        OperatorEntity op = new OperatorEntity("op-001", "tenant-a", "test@example.com", OperatorStatus.ACTIVE);
        op.setPasswordHash(passwordEncoder.encode("password123"));
        when(operatorRepo.findByTenantIdAndEmail("tenant-a", "test@example.com")).thenReturn(Optional.of(op));
        when(operatorRoleRepo.findByOperatorId("op-001")).thenReturn(List.of());
        when(jwtTokenProvider.createToken("op-001", "tenant-a", OperatorRole.VIEWER)).thenReturn("jwt-token");
        when(jwtTokenProvider.getExpirySeconds()).thenReturn(3600L);

        AuthService.LoginResult result = authService.login("test@example.com", "password123", "tenant-a");
        assertEquals(OperatorRole.VIEWER, result.operator().role());
    }

    @Test
    void refreshToken_success() {
        Claims claims = mock(Claims.class);
        when(claims.getId()).thenReturn("old-jti");
        when(claims.getSubject()).thenReturn("op-001");
        when(claims.get("tenantId", String.class)).thenReturn("tenant-a");
        when(claims.get("role", String.class)).thenReturn("engineer");
        when(claims.getExpiration()).thenReturn(Date.from(Instant.now().plusSeconds(3600)));

        when(jwtTokenProvider.parseToken("old-token")).thenReturn(claims);
        when(jwtTokenProvider.isRevoked("old-jti")).thenReturn(false);
        when(jwtTokenProvider.remainingTtl(claims)).thenReturn(java.time.Duration.ofMinutes(30));
        when(jwtTokenProvider.createToken("op-001", "tenant-a", OperatorRole.ENGINEER)).thenReturn("new-token");
        when(jwtTokenProvider.getExpirySeconds()).thenReturn(3600L);

        AuthService.LoginResult result = authService.refreshToken("old-token");
        assertEquals("new-token", result.token());
        verify(jwtTokenProvider).revokeToken("old-jti", java.time.Duration.ofMinutes(30));
    }

    @Test
    void refreshToken_revokedToken_throws() {
        Claims claims = mock(Claims.class);
        when(claims.getId()).thenReturn("old-jti");
        when(jwtTokenProvider.parseToken("old-token")).thenReturn(claims);
        when(jwtTokenProvider.isRevoked("old-jti")).thenReturn(true);

        AuthException ex = assertThrows(AuthException.class, () -> authService.refreshToken("old-token"));
        assertEquals(AuthError.TOKEN_REVOKED, ex.getError());
    }

    // Helper for Date import
    private static class Date {
        static java.util.Date from(Instant instant) { return java.util.Date.from(instant); }
    }
}

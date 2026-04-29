package com.opendota.security.auth;

import com.opendota.common.envelope.OperatorRole;
import com.opendota.security.model.OperatorEntity;
import com.opendota.security.model.OperatorRepository;
import com.opendota.security.model.OperatorRoleEntity;
import com.opendota.security.model.OperatorRoleRepository;
import com.opendota.security.model.OperatorStatus;
import com.opendota.security.model.RoleEntity;
import com.opendota.security.model.RoleRepository;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 认证服务:登录、刷新 token。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * 角色优先级,用于选择最高权限角色。
     */
    private static final Map<String, Integer> ROLE_PRIORITY = Map.of(
            "viewer", 1,
            "engineer", 2,
            "senior_engineer", 3,
            "admin", 4,
            "system", 5
    );

    private final OperatorRepository operatorRepo;
    private final OperatorRoleRepository operatorRoleRepo;
    private final RoleRepository roleRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(OperatorRepository operatorRepo,
                       OperatorRoleRepository operatorRoleRepo,
                       RoleRepository roleRepo,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider) {
        this.operatorRepo = operatorRepo;
        this.operatorRoleRepo = operatorRoleRepo;
        this.roleRepo = roleRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * 登录:校验邮箱 + 密码 + 账号状态,返回 JWT。
     */
    @Transactional
    public LoginResult login(String email, String password, String tenantId) {
        OperatorEntity operator = operatorRepo.findByTenantIdAndEmail(tenantId, email)
                .orElseThrow(() -> new AuthException(AuthError.INVALID_CREDENTIALS));

        if (operator.getStatus() != OperatorStatus.ACTIVE) {
            log.warn("登录失败:账号状态={} email={}", operator.getStatus(), email);
            throw new AuthException(AuthError.TOKEN_REVOKED);
        }

        if (operator.getPasswordHash() == null || !passwordEncoder.matches(password, operator.getPasswordHash())) {
            throw new AuthException(AuthError.INVALID_CREDENTIALS);
        }

        // 加载角色,选择最高优先级
        OperatorRole role = loadHighestRole(operator.getId());

        // 更新最后登录时间
        operator.setLastLoginAt(Instant.now());
        operatorRepo.save(operator);

        String token = jwtTokenProvider.createToken(operator.getId(), operator.getTenantId(), role);
        Instant expiresAt = Instant.now().plusSeconds(jwtTokenProvider.getExpirySeconds());

        log.info("登录成功 operatorId={} role={}", operator.getId(), role.wireName());
        return new LoginResult(token, expiresAt,
                new OperatorInfo(operator.getId(), operator.getDisplayName(), operator.getEmail(), role, operator.getTenantId()));
    }

    /**
     * 刷新 token:旧 token 必须未过期且未撤销。
     */
    @Transactional
    public LoginResult refreshToken(String oldToken) {
        Claims claims;
        try {
            claims = jwtTokenProvider.parseToken(oldToken);
        } catch (Exception e) {
            throw new AuthException(AuthError.TOKEN_EXPIRED);
        }

        String jti = claims.getId();
        if (jwtTokenProvider.isRevoked(jti)) {
            throw new AuthException(AuthError.TOKEN_REVOKED);
        }

        String operatorId = claims.getSubject();
        String tenantId = claims.get("tenantId", String.class);
        OperatorRole role = OperatorRole.of(claims.get("role", String.class));

        // 撤销旧 token
        jwtTokenProvider.revokeToken(jti, jwtTokenProvider.remainingTtl(claims));

        // 生成新 token
        String newToken = jwtTokenProvider.createToken(operatorId, tenantId, role);
        Instant expiresAt = Instant.now().plusSeconds(jwtTokenProvider.getExpirySeconds());

        log.info("Token 刷新 operatorId={}", operatorId);
        return new LoginResult(newToken, expiresAt,
                new OperatorInfo(operatorId, null, null, role, tenantId));
    }

    /**
     * 加载操作者最高优先级角色。
     */
    private OperatorRole loadHighestRole(String operatorId) {
        List<OperatorRoleEntity> roleEntities = operatorRoleRepo.findByOperatorId(operatorId);
        if (roleEntities.isEmpty()) {
            log.warn("操作者无角色分配,默认 VIEWER operatorId={}", operatorId);
            return OperatorRole.VIEWER;
        }

        return roleEntities.stream()
                .map(re -> roleRepo.findById(re.getRoleId()).orElse(null))
                .filter(r -> r != null)
                .max(Comparator.comparingInt(r -> ROLE_PRIORITY.getOrDefault(r.getRoleName(), 0)))
                .map(r -> OperatorRole.of(r.getRoleName()))
                .orElse(OperatorRole.VIEWER);
    }

    /**
     * 登录结果。
     */
    public record LoginResult(String token, Instant expiresAt, OperatorInfo operator) {}

    /**
     * 操作者信息(不含敏感字段)。
     */
    public record OperatorInfo(String id, String displayName, String email, OperatorRole role, String tenantId) {}
}

package com.opendota.security.audit;

import com.opendota.common.security.OperatorPrincipal;
import com.opendota.security.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;

/**
 * 安全审计日志服务:写入 security_audit_log 表(仅追加)。
 * 使用 JdbcTemplate 直接 SQL,不经过 JPA。
 */
@Service
public class SecurityAuditService {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditService.class);

    private final JdbcTemplate jdbcTemplate;

    public SecurityAuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 记录安全审计事件(完整上下文)。
     */
    public void record(String action, String resourceType, String result) {
        record(action, resourceType, result, null, null);
    }

    /**
     * 记录安全审计事件(含请求/响应载荷)。
     */
    public void record(String action, String resourceType, String result,
                       String reqPayload, String resPayload) {
        try {
            String auditId = UUID.randomUUID().toString();
            String msgId = MDC.get("msgId");
            String vin = MDC.get("vin");
            String ticketId = MDC.get("ticketId");
            String chainId = MDC.get("traceId");

            String tenantId = TenantContext.current();
            String operatorId = null;
            String operatorRole = null;

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof OperatorPrincipal principal) {
                operatorId = principal.operatorId();
                operatorRole = principal.role().wireName();
            }

            String clientIp = null;
            String userAgent = null;
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                clientIp = getClientIp(request);
                userAgent = request.getHeader("User-Agent");
            }

            jdbcTemplate.update(
                    "INSERT INTO security_audit_log (audit_id, msg_id, tenant_id, operator_id, operator_role, " +
                            "ticket_id, vin, action, resource_type, req_payload, res_payload, result, " +
                            "client_ip, user_agent, chain_id, timestamp) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    auditId, msgId, tenantId, operatorId, operatorRole,
                    ticketId, vin, action, resourceType, reqPayload, resPayload, result,
                    clientIp, userAgent, chainId, Instant.now()
            );
        } catch (Exception e) {
            log.error("安全审计日志写入失败: {}", e.getMessage(), e);
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) {
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank()) {
            return ip;
        }
        return request.getRemoteAddr();
    }
}

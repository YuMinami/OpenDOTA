package com.opendota.diag.api;

import com.opendota.common.envelope.Operator;
import com.opendota.common.envelope.OperatorRole;
import com.opendota.common.security.OperatorPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 从 HTTP 请求解析 {@link Operator}。
 *
 * <p>优先从 Spring SecurityContext 中提取 JWT 认证的 {@link OperatorPrincipal};
 * 无认证时 fallback 到 HTTP header(Phase 1 兼容)。
 */
@Component
public class OperatorContextResolver {

    public static final String HDR_OPERATOR_ID = "X-Operator-Id";
    public static final String HDR_OPERATOR_ROLE = "X-Operator-Role";
    public static final String HDR_TENANT_ID = "X-Tenant-Id";
    public static final String HDR_TICKET_ID = "X-Ticket-Id";

    public Operator resolve(HttpServletRequest request) {
        // 优先从 SecurityContext 获取(JWT 认证)
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof OperatorPrincipal principal) {
            String ticketId = trimToNull(request.getHeader(HDR_TICKET_ID));
            return principal.toOperator(ticketId);
        }

        // Fallback:从 HTTP header 读取(Phase 1 兼容)
        return resolveFromHeaders(request);
    }

    private Operator resolveFromHeaders(HttpServletRequest request) {
        String id = trimToNull(request.getHeader(HDR_OPERATOR_ID));
        String roleWire = trimToNull(request.getHeader(HDR_OPERATOR_ROLE));
        String tenantId = trimToNull(request.getHeader(HDR_TENANT_ID));
        String ticketId = trimToNull(request.getHeader(HDR_TICKET_ID));

        if (id == null) {
            return Operator.system(tenantId == null ? "default-tenant" : tenantId);
        }
        OperatorRole role = roleWire == null ? OperatorRole.ENGINEER : OperatorRole.of(roleWire);
        return new Operator(id, role, tenantId == null ? "default-tenant" : tenantId, ticketId);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}

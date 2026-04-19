package com.opendota.diag.api;

import com.opendota.common.envelope.Operator;
import com.opendota.common.envelope.OperatorRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 从 HTTP 请求解析 {@link Operator}。
 *
 * <p>Phase 1 MVP 的过渡实现:从 HTTP header 读取
 * {@code X-Operator-Id / X-Operator-Role / X-Tenant-Id / X-Ticket-Id}。
 * 生产实现(opendota-security)会改为从 JWT principal 抽取,并做租户证书一致性校验。
 *
 * <p>兜底:缺 header 时返回 {@link Operator#system(String)},防止下游 NPE。
 * 这样即使 Phase 1 联调没接认证,整条下发链路依然可以跑通。
 */
@Component
public class OperatorContextResolver {

    public static final String HDR_OPERATOR_ID = "X-Operator-Id";
    public static final String HDR_OPERATOR_ROLE = "X-Operator-Role";
    public static final String HDR_TENANT_ID = "X-Tenant-Id";
    public static final String HDR_TICKET_ID = "X-Ticket-Id";

    public Operator resolve(HttpServletRequest request) {
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

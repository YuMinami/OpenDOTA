package com.opendota.common.security;

import com.opendota.common.envelope.Operator;
import com.opendota.common.envelope.OperatorRole;

import java.security.Principal;

/**
 * JWT 认证后的操作者主体,存放在 Spring SecurityContext 中。
 * 放在 opendota-common 以避免 opendota-diag → opendota-security 循环依赖。
 *
 * @param operatorId 操作员 ID(JWT.sub)
 * @param tenantId   租户 ID
 * @param role       角色
 * @param jti        JWT ID(用于撤销)
 */
public record OperatorPrincipal(
        String operatorId,
        String tenantId,
        OperatorRole role,
        String jti
) implements Principal {

    @Override
    public String getName() {
        return operatorId;
    }

    /**
     * 转换为 MQTT 信封使用的 Operator record。
     *
     * @param ticketId 工单号,从 HTTP header X-Ticket-Id 获取
     */
    public Operator toOperator(String ticketId) {
        return new Operator(operatorId, role, tenantId, ticketId);
    }
}

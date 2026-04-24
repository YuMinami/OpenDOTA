package com.opendota.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Java 模拟器写入 security_audit_log 的最小仓储。
 *
 * <p>Phase 1 conformance 只需要验证 envelope 守卫是否落审计,因此这里只落最小字段集。
 */
public class SimulatorSecurityAuditRepository {

    private static final Logger log = LoggerFactory.getLogger(SimulatorSecurityAuditRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public SimulatorSecurityAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void recordEnvelopeRejected(SimulatorProperties.VehicleProfile profile, JsonNode envelope, String action) {
        JsonNode operator = envelope.path("operator");
        String tenantId = firstNonBlank(
                textOrNull(operator, "tenantId"),
                profile.getTenantId(),
                "default-tenant");
        try {
            jdbcTemplate.update("""
                            INSERT INTO security_audit_log (
                                audit_id, msg_id, tenant_id, operator_id, operator_role, ticket_id,
                                vin, action, resource_type, result
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    UUID.randomUUID().toString(),
                    textOrNull(envelope, "msgId"),
                    tenantId,
                    textOrNull(operator, "id"),
                    textOrNull(operator, "role"),
                    textOrNull(operator, "ticketId"),
                    profile.getVin(),
                    action,
                    "ENVELOPE",
                    "rejected");
        } catch (Exception e) {
            log.error("写 security_audit_log 失败 vin={} action={}", profile.getVin(), action, e);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

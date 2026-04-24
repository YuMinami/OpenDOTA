package com.opendota.common.logging;

/**
 * 诊断链路统一 MDC 键。
 *
 * <p>HTTP / MQTT / 后续异步边界都必须沿用同一套 key,避免日志检索条件分裂。
 */
public final class DiagMdcKeys {

    public static final String TRACE_ID = "traceId";
    public static final String MSG_ID = "msgId";
    public static final String VIN = "vin";
    public static final String OPERATOR_ID = "operatorId";
    public static final String TENANT_ID = "tenantId";
    public static final String TICKET_ID = "ticketId";

    public static final String NO_VALUE = "-";
    public static final String SYSTEM_OPERATOR_ID = "system";
    public static final String DEFAULT_TENANT_ID = "default-tenant";

    private DiagMdcKeys() {
    }

    public static String dashIfBlank(String value) {
        if (value == null) {
            return NO_VALUE;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? NO_VALUE : trimmed;
    }
}

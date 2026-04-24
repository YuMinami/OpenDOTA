package com.opendota.mqtt.subscriber;

import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.Operator;
import org.slf4j.MDC;

import java.util.Map;

/**
 * MQTT subscriber MDC 五字段绑定器。使用 try-with-resources 保障消息处理完成后恢复现场。
 */
public class MdcBinder {

    static final String MDC_TRACE_ID = "traceId";
    static final String MDC_MSG_ID = "msgId";
    static final String MDC_VIN = "vin";
    static final String MDC_OPERATOR_ID = "operatorId";
    static final String MDC_TENANT_ID = "tenantId";

    public Scope bind(DiagMessage<?> envelope) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.put(MDC_TRACE_ID, traceIdOf(envelope.traceparent()));
        MDC.put(MDC_MSG_ID, envelope.msgId());
        MDC.put(MDC_VIN, envelope.vin());
        Operator operator = envelope.operator();
        MDC.put(MDC_OPERATOR_ID, operator == null ? "system" : String.valueOf(operator.id()));
        MDC.put(MDC_TENANT_ID, operator == null || operator.tenantId() == null ? "-" : operator.tenantId());
        return () -> restore(previous);
    }

    static String traceIdOf(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return "-";
        }
        String[] parts = traceparent.split("-");
        return parts.length >= 2 && !parts[1].isBlank() ? parts[1] : "-";
    }

    private void restore(Map<String, String> previous) {
        MDC.clear();
        if (previous != null && !previous.isEmpty()) {
            MDC.setContextMap(previous);
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}

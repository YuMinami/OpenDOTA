package com.opendota.mqtt.subscriber;

import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.Operator;
import com.opendota.common.logging.DiagMdcKeys;
import com.opendota.common.util.TraceparentUtils;
import org.slf4j.MDC;

import java.util.Map;

/**
 * MQTT subscriber MDC 六字段绑定器。使用 try-with-resources 保障消息处理完成后恢复现场。
 */
public class MdcBinder {

    public Scope bind(DiagMessage<?> envelope) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.put(DiagMdcKeys.TRACE_ID, traceIdOf(envelope.traceparent()));
        MDC.put(DiagMdcKeys.MSG_ID, DiagMdcKeys.dashIfBlank(envelope.msgId()));
        MDC.put(DiagMdcKeys.VIN, DiagMdcKeys.dashIfBlank(envelope.vin()));
        Operator operator = envelope.operator();
        MDC.put(DiagMdcKeys.OPERATOR_ID, operator == null
                ? DiagMdcKeys.SYSTEM_OPERATOR_ID
                : DiagMdcKeys.dashIfBlank(operator.id()));
        MDC.put(DiagMdcKeys.TENANT_ID, operator == null
                ? DiagMdcKeys.NO_VALUE
                : DiagMdcKeys.dashIfBlank(operator.tenantId()));
        MDC.put(DiagMdcKeys.TICKET_ID, operator == null
                ? DiagMdcKeys.NO_VALUE
                : DiagMdcKeys.dashIfBlank(operator.ticketId()));
        return () -> restore(previous);
    }

    static String traceIdOf(String traceparent) {
        return TraceparentUtils.extractTraceId(traceparent).orElse(DiagMdcKeys.NO_VALUE);
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

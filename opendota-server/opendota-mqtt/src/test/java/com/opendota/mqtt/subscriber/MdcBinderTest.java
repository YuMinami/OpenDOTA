package com.opendota.mqtt.subscriber;

import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MdcBinderTest {

    private final MdcBinder binder = new MdcBinder();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void bindUsesFallbackValuesForMissingOperatorAndRestoresPreviousContext() {
        MDC.put("traceId", "old-trace");
        MDC.put("msgId", "old-msg");

        DiagMessage<Object> envelope = new DiagMessage<>(
                "msg-002",
                1713301200000L,
                "LSVWA234567890123",
                DiagAction.SINGLE_RESP,
                null,
                null,
                null
        );

        try (MdcBinder.Scope ignored = binder.bind(envelope)) {
            assertEquals("-", MDC.get("traceId"));
            assertEquals("msg-002", MDC.get("msgId"));
            assertEquals("LSVWA234567890123", MDC.get("vin"));
            assertEquals("system", MDC.get("operatorId"));
            assertEquals("-", MDC.get("tenantId"));
            assertEquals("-", MDC.get("ticketId"));
        }

        assertEquals("old-trace", MDC.get("traceId"));
        assertEquals("old-msg", MDC.get("msgId"));
        assertNull(MDC.get("vin"));
        assertNull(MDC.get("operatorId"));
        assertNull(MDC.get("tenantId"));
        assertNull(MDC.get("ticketId"));
    }
}

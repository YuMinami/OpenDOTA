package com.opendota.observability.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DiagTraceFilterTest {

    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");

    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> appender;
    private ListAppender<ILoggingEvent> filterAppender;
    private Logger probeLogger;
    private Logger filterLogger;

    @BeforeEach
    void setUp() {
        probeLogger = (Logger) LoggerFactory.getLogger(ProbeController.class);
        appender = new ListAppender<>();
        appender.start();
        probeLogger.addAppender(appender);
        filterLogger = (Logger) LoggerFactory.getLogger(DiagTraceFilter.class);
        filterAppender = new ListAppender<>();
        filterAppender.start();
        filterLogger.addAppender(filterAppender);

        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(new DiagTraceFilter())
                .build();
    }

    @AfterEach
    void tearDown() {
        probeLogger.detachAppender(appender);
        appender.stop();
        filterLogger.detachAppender(filterAppender);
        filterAppender.stop();
        MDC.clear();
    }

    @Test
    void shouldInjectMdcForHttpRequestLog() throws Exception {
        mockMvc.perform(get("/probe/log")
                        .header("traceparent", "00-12345678901234567890123456789012-1234567890abcdef-01")
                        .header("X-Operator-Id", "operator-1")
                        .header("X-Tenant-Id", "tenant-1")
                        .header("X-Ticket-Id", "ticket-1"))
                .andExpect(status().isOk());

        ILoggingEvent event = singleProbeEvent();
        Map<String, String> mdc = event.getMDCPropertyMap();
        assertEquals("12345678901234567890123456789012", mdc.get("traceId"));
        assertEquals("-", mdc.get("msgId"));
        assertEquals("-", mdc.get("vin"));
        assertEquals("operator-1", mdc.get("operatorId"));
        assertEquals("tenant-1", mdc.get("tenantId"));
        assertEquals("ticket-1", mdc.get("ticketId"));
        assertEquals("12345678901234567890123456789012", filterEvent().getMDCPropertyMap().get("traceId"));

        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("operatorId"));
        assertNull(MDC.get("tenantId"));
        assertNull(MDC.get("ticketId"));
    }

    @Test
    void shouldGenerateTraceIdAndFallbackDefaultsWhenHeadersMissing() throws Exception {
        mockMvc.perform(get("/probe/log"))
                .andExpect(status().isOk());

        ILoggingEvent event = singleProbeEvent();
        Map<String, String> mdc = event.getMDCPropertyMap();
        assertTrue(TRACE_ID_PATTERN.matcher(mdc.get("traceId")).matches());
        assertEquals("system", mdc.get("operatorId"));
        assertEquals("default-tenant", mdc.get("tenantId"));
        assertEquals("-", mdc.get("ticketId"));
    }

    @Test
    void shouldIgnoreInvalidTraceparent() throws Exception {
        mockMvc.perform(get("/probe/log")
                        .header("traceparent", "00-00000000000000000000000000000000-1234567890abcdef-01"))
                .andExpect(status().isOk());

        ILoggingEvent event = singleProbeEvent();
        String traceId = event.getMDCPropertyMap().get("traceId");
        assertTrue(TRACE_ID_PATTERN.matcher(traceId).matches());
        assertFalse("00000000000000000000000000000000".equals(traceId));
    }

    private ILoggingEvent singleProbeEvent() {
        List<ILoggingEvent> events = appender.list.stream()
                .filter(event -> "probe controller reached".equals(event.getFormattedMessage()))
                .toList();
        assertEquals(1, events.size());
        return events.getFirst();
    }

    private ILoggingEvent filterEvent() {
        List<ILoggingEvent> events = filterAppender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("HTTP GET /probe/log"))
                .toList();
        assertEquals(1, events.size());
        return events.getFirst();
    }

    @RestController
    static class ProbeController {

        private static final org.slf4j.Logger log = LoggerFactory.getLogger(ProbeController.class);

        @GetMapping("/probe/log")
        Map<String, Object> log() {
            log.info("probe controller reached");
            return Map.of("ok", true);
        }
    }
}

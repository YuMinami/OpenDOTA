package com.opendota.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceparentUtilsTest {

    @Test
    void extractTraceIdReturnsLowercaseTraceIdForValidTraceparent() {
        assertEquals(
                "4bf92f3577b34da6a3ce929d0e0e4736",
                TraceparentUtils.extractTraceId(" 00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01 ")
                        .orElseThrow()
        );
    }

    @Test
    void extractTraceIdRejectsBlankMalformedAndAllZeroTraceIds() {
        assertTrue(TraceparentUtils.extractTraceId(null).isEmpty());
        assertTrue(TraceparentUtils.extractTraceId(" ").isEmpty());
        assertTrue(TraceparentUtils.extractTraceId("00-not-a-trace-id-00f067aa0ba902b7-01").isEmpty());
        assertTrue(TraceparentUtils.extractTraceId("00-00000000000000000000000000000000-00f067aa0ba902b7-01").isEmpty());
    }

    @Test
    void extractOrGenerateTraceIdFallsBackToGeneratedTraceId() {
        String generated = TraceparentUtils.extractOrGenerateTraceId("invalid");

        assertEquals(32, generated.length());
        assertTrue(generated.matches("[0-9a-f]{32}"));
        assertNotEquals("00000000000000000000000000000000", generated);
    }

    @Test
    void generateTraceIdReturnsLowercaseHexWithoutDashes() {
        String generated = TraceparentUtils.generateTraceId();

        assertEquals(32, generated.length());
        assertTrue(generated.matches("[0-9a-f]{32}"));
        assertFalse(generated.contains("-"));
    }
}

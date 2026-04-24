package com.opendota.common.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiagMdcKeysTest {

    @Test
    void dashIfBlankReturnsDashForNullOrWhitespace() {
        assertEquals(DiagMdcKeys.NO_VALUE, DiagMdcKeys.dashIfBlank(null));
        assertEquals(DiagMdcKeys.NO_VALUE, DiagMdcKeys.dashIfBlank("   "));
        assertEquals("trace-001", DiagMdcKeys.dashIfBlank(" trace-001 "));
    }
}

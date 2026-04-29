package com.opendota.common.payload.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroTypeTest {

    @Test
    void wireNameMatchesProtocolSpec() {
        assertEquals("macro_security", MacroType.MACRO_SECURITY.wireName());
        assertEquals("macro_routine_wait", MacroType.MACRO_ROUTINE_WAIT.wireName());
        assertEquals("macro_data_transfer", MacroType.MACRO_DATA_TRANSFER.wireName());
    }

    @Test
    void isMacroReturnsTrueForKnownTypes() {
        assertTrue(MacroType.isMacro("macro_security"));
        assertTrue(MacroType.isMacro("macro_routine_wait"));
        assertTrue(MacroType.isMacro("macro_data_transfer"));
    }

    @Test
    void isMacroReturnsFalseForUnknownOrNull() {
        assertFalse(MacroType.isMacro(null));
        assertFalse(MacroType.isMacro("raw_uds"));
        assertFalse(MacroType.isMacro("unknown_macro"));
        assertFalse(MacroType.isMacro(""));
    }

    @Test
    void fromWireNameReturnsCorrectType() {
        assertEquals(MacroType.MACRO_SECURITY, MacroType.fromWireName("macro_security"));
        assertEquals(MacroType.MACRO_ROUTINE_WAIT, MacroType.fromWireName("macro_routine_wait"));
        assertEquals(MacroType.MACRO_DATA_TRANSFER, MacroType.fromWireName("macro_data_transfer"));
    }

    @Test
    void fromWireNameReturnsNullForUnknown() {
        assertNull(MacroType.fromWireName(null));
        assertNull(MacroType.fromWireName("raw_uds"));
        assertNull(MacroType.fromWireName("unknown"));
    }

    @Test
    void macroSecurityRequiresLevelAndAlgoId() {
        assertEquals(2, MacroType.MACRO_SECURITY.requiredParams().size());
        assertTrue(MacroType.MACRO_SECURITY.requiredParams().contains("level"));
        assertTrue(MacroType.MACRO_SECURITY.requiredParams().contains("algoId"));
    }

    @Test
    void macroRoutineWaitRequiresDataAndMaxWaitMs() {
        assertEquals(2, MacroType.MACRO_ROUTINE_WAIT.requiredParams().size());
        assertTrue(MacroType.MACRO_ROUTINE_WAIT.requiredParams().contains("data"));
        assertTrue(MacroType.MACRO_ROUTINE_WAIT.requiredParams().contains("maxWaitMs"));
    }

    @Test
    void macroDataTransferRequiresFourFields() {
        assertEquals(4, MacroType.MACRO_DATA_TRANSFER.requiredParams().size());
        assertTrue(MacroType.MACRO_DATA_TRANSFER.requiredParams().contains("direction"));
        assertTrue(MacroType.MACRO_DATA_TRANSFER.requiredParams().contains("transferSessionId"));
        assertTrue(MacroType.MACRO_DATA_TRANSFER.requiredParams().contains("fileSha256"));
        assertTrue(MacroType.MACRO_DATA_TRANSFER.requiredParams().contains("fileSize"));
    }

    @Test
    void toStringReturnsWireName() {
        assertEquals("macro_security", MacroType.MACRO_SECURITY.toString());
        assertEquals("macro_routine_wait", MacroType.MACRO_ROUTINE_WAIT.toString());
    }
}

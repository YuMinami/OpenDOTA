package com.opendota.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexUtilsTest {

    @Test
    void fromHexAcceptsPrefixAndUppercaseInput() {
        assertArrayEquals(new byte[]{(byte) 0xAB, (byte) 0xCD}, HexUtils.fromHex("0xABCD"));
        assertArrayEquals(new byte[]{0x01, 0x02}, HexUtils.fromHex("0102"));
    }

    @Test
    void fromHexRejectsNullEmptyAndOddLengthInput() {
        assertEquals("hex 不能为空", assertThrows(IllegalArgumentException.class, () -> HexUtils.fromHex(null)).getMessage());
        assertEquals("hex 不能为空", assertThrows(IllegalArgumentException.class, () -> HexUtils.fromHex("")).getMessage());
        assertEquals(
                "hex 长度必须为偶数: ABC",
                assertThrows(IllegalArgumentException.class, () -> HexUtils.fromHex("ABC")).getMessage()
        );
    }

    @Test
    void responseHelpersHandlePositiveAndNegativeFrames() {
        assertTrue(HexUtils.isPositiveResponse(new byte[]{0x10}, new byte[]{0x50}));
        assertFalse(HexUtils.isPositiveResponse(new byte[]{0x10}, new byte[]{0x7F, 0x10, 0x13}));
        assertFalse(HexUtils.isPositiveResponse(null, new byte[]{0x50}));
        assertFalse(HexUtils.isPositiveResponse(new byte[]{0x10}, null));
        assertFalse(HexUtils.isPositiveResponse(new byte[0], new byte[]{0x50}));

        assertTrue(HexUtils.isNegativeResponse(new byte[]{0x7F, 0x10, 0x13}));
        assertFalse(HexUtils.isNegativeResponse(new byte[]{0x7F, 0x10}));
        assertFalse(HexUtils.isNegativeResponse(new byte[]{0x50}));
        assertFalse(HexUtils.isNegativeResponse(null));
    }
}

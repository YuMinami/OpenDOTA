package com.opendota.common.util;

import java.util.HexFormat;

/**
 * UDS 十六进制字符串工具。
 *
 * <p>OpenDOTA 约定所有 hex 字段(如 {@code txId}/{@code rxId}/{@code reqData}/{@code resData})
 * 统一使用**小写无分隔**格式。canonical 序列化前兜底再做一次 lowercase,见
 * {@code doc/schema/payload_hash_canonicalization.md §4.1}。
 */
public final class HexUtils {

    private static final HexFormat LOWER = HexFormat.of();

    private HexUtils() {
    }

    /**
     * 把字节数组转为小写无分隔 hex 字符串,如 {@code 0xAB 0xCD → "abcd"}。
     */
    public static String toHex(byte[] bytes) {
        return LOWER.formatHex(bytes);
    }

    /**
     * 把 hex 字符串(大小写/带 0x 前缀均可)转为字节数组。
     * 空或奇数长度抛 {@link IllegalArgumentException}。
     */
    public static byte[] fromHex(String hex) {
        if (hex == null || hex.isEmpty()) {
            throw new IllegalArgumentException("hex 不能为空");
        }
        String normalized = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if (normalized.length() % 2 != 0) {
            throw new IllegalArgumentException("hex 长度必须为偶数: " + hex);
        }
        return LOWER.parseHex(normalized.toLowerCase());
    }

    /**
     * 规范化 hex 字符串,保留 {@code 0x} 前缀并统一为小写。
     * 例如:{@code "0X7E0" → "0x7e0"},{@code "7E0" → "0x7e0"}。
     */
    public static String normalizeAddr(String addr) {
        if (addr == null) {
            return null;
        }
        String lower = addr.toLowerCase();
        return lower.startsWith("0x") ? lower : "0x" + lower;
    }

    /**
     * UDS 肯定响应判定:{@code response[0] == request[0] + 0x40}。
     */
    public static boolean isPositiveResponse(byte[] request, byte[] response) {
        if (request == null || response == null || request.length == 0 || response.length == 0) {
            return false;
        }
        return (response[0] & 0xFF) == ((request[0] & 0xFF) + 0x40);
    }

    /**
     * UDS 否定响应判定:{@code response[0] == 0x7F}。
     */
    public static boolean isNegativeResponse(byte[] response) {
        return response != null && response.length >= 3 && (response[0] & 0xFF) == 0x7F;
    }
}

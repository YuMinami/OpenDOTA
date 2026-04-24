package com.opendota.common.payload.common;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * ECU scope 规范化工具。
 *
 * <p>协议要求 {@code ecuScope} 在锁获取和 payloadHash 计算前按字典序稳定排序。
 * 本工具不做去重,避免悄悄掩盖上游构造错误。
 */
public final class EcuScope {

    private EcuScope() {
    }

    public static List<String> normalize(List<String> ecuScope) {
        if (ecuScope == null || ecuScope.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>(ecuScope.size());
        for (String ecu : ecuScope) {
            String trimmed = Objects.requireNonNull(ecu, "ecuScope 元素不能为空").trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("ecuScope 元素不能为空白");
            }
            normalized.add(trimmed);
        }
        normalized.sort(Comparator.naturalOrder());
        return List.copyOf(normalized);
    }
}

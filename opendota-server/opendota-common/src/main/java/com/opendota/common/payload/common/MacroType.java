package com.opendota.common.payload.common;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 车端宏指令类型(协议 §7.2)。
 *
 * <p>云端仅负责透传宏 payload,宏执行逻辑在车端 Agent 闭环。
 * 本枚举用于:
 * <ul>
 *   <li>下发时校验必填参数</li>
 *   <li>审计日志记录 macroType</li>
 *   <li>Prometheus 按宏类型分维度计数</li>
 * </ul>
 */
public enum MacroType {

    /** 安全访问宏 — Seed-to-Key(协议 §7.3) */
    MACRO_SECURITY("macro_security", orderedSet("level", "algoId")),

    /** 例程等待宏 — 启动 + 轮询(协议 §7.4) */
    MACRO_ROUTINE_WAIT("macro_routine_wait", orderedSet("data", "maxWaitMs")),

    /** 数据传输宏 — OTA / 大数据读取(协议 §7.5) */
    MACRO_DATA_TRANSFER("macro_data_transfer", orderedSet("direction", "transferSessionId", "fileSha256", "fileSize"));

    private static final Map<String, MacroType> WIRE_MAP = Map.of(
            MACRO_SECURITY.wireName, MACRO_SECURITY,
            MACRO_ROUTINE_WAIT.wireName, MACRO_ROUTINE_WAIT,
            MACRO_DATA_TRANSFER.wireName, MACRO_DATA_TRANSFER
    );

    private final String wireName;
    private final Set<String> requiredParams;

    MacroType(String wireName, Set<String> requiredParams) {
        this.wireName = wireName;
        this.requiredParams = requiredParams;
    }

    private static Set<String> orderedSet(String... items) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String item : items) {
            set.add(item);
        }
        return Collections.unmodifiableSet(set);
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    public Set<String> requiredParams() {
        return requiredParams;
    }

    /**
     * 判断给定 step type 是否为宏类型。
     */
    public static boolean isMacro(String type) {
        return type != null && WIRE_MAP.containsKey(type);
    }

    /**
     * 从 wire name 解析宏类型,未知类型返回 {@code null}。
     */
    public static MacroType fromWireName(String wireName) {
        if (wireName == null) {
            return null;
        }
        return WIRE_MAP.get(wireName);
    }

    @Override
    public String toString() {
        return wireName;
    }
}

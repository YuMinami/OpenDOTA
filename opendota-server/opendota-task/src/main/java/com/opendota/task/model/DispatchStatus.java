package com.opendota.task.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * task_dispatch_record.dispatch_status 11 个状态枚举。
 *
 * <p>权威来源: 协议 §14.4.3 域 2 分发记录状态。状态机:
 * <pre>
 *   pending_online ──下发──► dispatched ──车端确认──► queued ──执行──► executing ──┐
 *                                                       │                          │
 *                                                       ▼                          ▼
 *                                                   scheduling                 completed/failed
 *                                                       │                          ▲
 *                                                       └──下次触发───► executing ──┘
 *
 *   任意非终态 → paused / deferred / canceled / expired
 * </pre>
 *
 * <p>4 个终态: {@link #COMPLETED} / {@link #FAILED} / {@link #CANCELED} / {@link #EXPIRED}。
 *
 * <p>使用约定: 新增进度聚合代码统一用本枚举,避免散字符串。已有代码
 * (如 {@code TaskDispatchRecord.dispatchStatus} String 字段、JPA Query)在
 * Step 4.8 阶段不重构,保持 PR 体量。
 */
public enum DispatchStatus {

    PENDING_ONLINE("pending_online"),
    DISPATCHED("dispatched"),
    QUEUED("queued"),
    SCHEDULING("scheduling"),
    EXECUTING("executing"),
    PAUSED("paused"),
    DEFERRED("deferred"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELED("canceled"),
    EXPIRED("expired");

    private final String wireName;

    DispatchStatus(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static DispatchStatus of(String wireName) {
        if (wireName == null) {
            return null;
        }
        for (DispatchStatus s : values()) {
            if (s.wireName.equalsIgnoreCase(wireName)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知 DispatchStatus: " + wireName);
    }

    /**
     * 是否终态(进度计算时不再产生新事件)。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED || this == EXPIRED;
    }
}

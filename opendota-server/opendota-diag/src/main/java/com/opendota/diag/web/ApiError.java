package com.opendota.diag.web;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * OpenDOTA REST 业务错误码枚举(REST §3)。
 */
public enum ApiError {
    SUCCESS(0, "success"),

    // ============== 通用(REST §3.1) ==============
    E40001(40001, "参数缺失"),
    E40002(40002, "参数格式错误"),
    E40003(40003, "参数超出允许范围"),
    E40101(40101, "未登录"),
    E40102(40102, "Token 过期"),
    E40103(40103, "Token 被撤销"),
    E40301(40301, "RBAC 权限不足"),
    E40302(40302, "需二次审批"),
    E40401(40401, "资源不存在"),
    E40901(40901, "资源重复"),
    E42301(42301, "车端资源被占用"),
    E42900(42900, "触发限流"),
    E50001(50001, "内部错误"),
    E50301(50301, "下游不可用"),
    E50401(50401, "下发超时"),

    // ============== 任务域(REST §3.2) ==============
    E40305(40305, "不可中断宏不允许 force_cancel"),
    E41001(41001, "maxExecutions=-1 时 executeValidUntil 必填"),
    E41002(41002, "ecuScope 必填缺失"),
    E41003(41003, "ecuScope 中 ECU 不存在于车型 ODX"),
    E41004(41004, "scheduleType 与 scheduleConfig 不匹配"),
    E41005(41005, "payloadHash 与 diagPayload 计算值不符"),
    E41006(41006, "signalCatalogVersion 缺失或落后"),
    E41007(41007, "targetScope 解析出的目标车辆数为 0"),
    E41008(41008, "supersedes 指定的旧任务不存在或不在同一租户"),
    E41010(41010, "任务执行窗口 executeValidUntil < validUntil"),
    E41011(41011, "executeValidFrom < validFrom"),
    E41012(41012, "dynamic targetScope 仅允许 periodic/conditional scheduleType"),

    // ============== 车端反馈(REST §3.3) ==============
    E42302(42302, "车端队列已满"),
    E42303(42303, "车端 signal catalog 版本落后"),
    E42304(42304, "ECU 已被占用"),
    E42305(42305, "当前任务不可抢占"),
    E42306(42306, "车端时钟不可信,定时任务已挂起");

    private static final Map<Integer, ApiError> INDEX = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ApiError::code, Function.identity()));

    private final int code;
    private final String defaultMessage;

    ApiError(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public static ApiError fromCode(int code) {
        ApiError error = INDEX.get(code);
        if (error == null) {
            throw new IllegalArgumentException("未知 ApiError code: " + code);
        }
        return error;
    }
}

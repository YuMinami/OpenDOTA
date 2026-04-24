package com.opendota.common.envelope;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 车云协议 act 枚举(协议 §3.4)。
 *
 * <p>序列化/反序列化遵循**小写下划线**线格式(如 {@code single_cmd}),与 MQTT 报文 JSON 字段保持一致。
 * 新增 act 时必须同步更新协议规范 §3.4 表格和 REST 错误码 §3 任务域。
 *
 * <p>命名约定:
 * <ul>
 *   <li>C2V(Cloud → Vehicle): {@code _cmd} / {@code _query} / {@code _pause} / {@code _resume} / {@code _cancel}</li>
 *   <li>V2C(Vehicle → Cloud): {@code _resp} / {@code _event} / {@code _ack} / {@code _status} / {@code _reject}</li>
 *   <li>双向事件: {@code time_sync_*} / {@code execution_*} / {@code condition_fired}</li>
 * </ul>
 */
public enum DiagAction {

    // ============== 通道生命周期(§4) ==============
    CHANNEL_OPEN("channel_open"),
    CHANNEL_CLOSE("channel_close"),
    CHANNEL_EVENT("channel_event"),
    CHANNEL_READY("channel_ready"),

    // ============== 单步诊断(§5) ==============
    SINGLE_CMD("single_cmd"),
    SINGLE_RESP("single_resp"),

    // ============== 批量诊断(§6) ==============
    BATCH_CMD("batch_cmd"),
    BATCH_RESP("batch_resp"),

    // ============== 定时/周期/条件任务(§8) ==============
    SCHEDULE_SET("schedule_set"),
    SCHEDULE_CANCEL("schedule_cancel"),
    SCHEDULE_RESP("schedule_resp"),

    // ============== 多 ECU 脚本(§9) ==============
    SCRIPT_CMD("script_cmd"),
    SCRIPT_RESP("script_resp"),

    // ============== 车端队列操控(§11) ==============
    QUEUE_QUERY("queue_query"),
    QUEUE_DELETE("queue_delete"),
    QUEUE_PAUSE("queue_pause"),
    QUEUE_RESUME("queue_resume"),
    QUEUE_STATUS("queue_status"),
    QUEUE_REJECT("queue_reject"),

    // ============== 任务控制与幂等(§12) ==============
    TASK_PAUSE("task_pause"),
    TASK_RESUME("task_resume"),
    TASK_CANCEL("task_cancel"),
    TASK_QUERY("task_query"),
    TASK_ACK("task_ack"),

    // ============== 条件任务命中(§8.3) ==============
    CONDITION_FIRED("condition_fired"),

    // ============== 周期执行双 ack(§8.5.1) ==============
    EXECUTION_BEGIN("execution_begin"),
    EXECUTION_END("execution_end"),

    // ============== 时钟信任(§17) ==============
    TIME_SYNC_REQUEST("time_sync_request"),
    TIME_SYNC_RESPONSE("time_sync_response"),

    // ============== 信号白名单(§8.3.2.1) ==============
    SIGNAL_CATALOG_PUSH("signal_catalog_push"),
    SIGNAL_CATALOG_ACK("signal_catalog_ack"),

    // ============== 容错兜底 ==============
    UNKNOWN("unknown");

    private final String wireName;

    DiagAction(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static DiagAction of(String wireName) {
        for (DiagAction a : values()) {
            if (a.wireName.equals(wireName)) {
                return a;
            }
        }
        throw new IllegalArgumentException("未知 DiagAction: " + wireName);
    }

    public static DiagAction ofOrUnknown(String wireName) {
        if (wireName == null || wireName.isBlank()) {
            return UNKNOWN;
        }
        for (DiagAction a : values()) {
            if (a.wireName.equals(wireName)) {
                return a;
            }
        }
        return UNKNOWN;
    }
}

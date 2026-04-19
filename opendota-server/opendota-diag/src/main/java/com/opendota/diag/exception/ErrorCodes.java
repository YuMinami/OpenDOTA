package com.opendota.diag.exception;

/**
 * OpenDOTA 业务错误码常量(REST §3)。
 *
 * <p>用 {@code int} 而不是 enum 主要是为了让 {@code code} 直接能写入 {@code {code,msg,data}} 包装,
 * 避免前端再映射一次。新增错误码时同步更新 {@code doc/opendota_rest_api_spec.md §3}。
 */
public final class ErrorCodes {

    private ErrorCodes() {}

    // ============== 通用 / RBAC(REST §3.1)==============
    public static final int SUCCESS = 0;
    public static final int BAD_REQUEST = 40001;
    public static final int RBAC_FORBIDDEN = 40301;

    // ============== 任务域(REST §3.2)==============
    /** v1.4 A2:不可中断宏(macro_data_transfer / macro_security 半程)不允许 force_cancel。 */
    public static final int NON_INTERRUPTIBLE_MACRO = 40305;

    /** channelId 不存在、已超时、已 replay 或已关闭。 */
    public static final int CHANNEL_NOT_FOUND = 40401;

    /** 下发超时(MQTT publish 失败或未 ack)。 */
    public static final int DISPATCH_TIMEOUT = 50401;
}

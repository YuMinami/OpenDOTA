package com.opendota.diag.dispatch;

import java.util.Set;

/**
 * v1.4 A2:不可中断宏白名单(协议 §12.4.1)。
 *
 * <pre>
 *   macro_data_transfer — 中断会让 ECU 分区处于不一致写入状态,冷启动可能 brick
 *   macro_security       — 中断(已发 seed 未发 key)会触发 ECU 的 Anti-Scan 锁定
 * </pre>
 *
 * 一律拒绝 {@code force_cancel},无 admin 强制开关。试图 force 的请求由云端 Dispatcher 返回
 * {@code code=40305 non_interruptible_macro} 并写入 {@code security_audit_log}(后续接入)。
 */
public final class NonInterruptibleMacros {

    /** 不可中断宏类型,与协议 §12.4.1 保持一致。 */
    public static final Set<String> PROTECTED = Set.of(
            "macro_data_transfer",
            "macro_security"
    );

    private NonInterruptibleMacros() {}

    /** 给定宏类型是否属于不可中断白名单;{@code null} 视为未执行宏,返回 false。 */
    public static boolean isProtected(String macroType) {
        return macroType != null && PROTECTED.contains(macroType);
    }
}

package com.opendota.task.dispatch;

/**
 * 分发来源枚举(架构 §4.4.4)。
 *
 * <p>v1.4 A1/B2 决策:仅 {@link #OFFLINE_REPLAY} 和 {@link #BATCH_THROTTLED} 走 jitter 分桶,
 * 在线车首次分发立即执行,保障 SLO。
 */
public enum DispatchSource {

    /** 在线车首次分发 / channel_open,立即执行不走 jitter */
    ONLINE_IMMEDIATE,

    /** 100-10000 批量任务中的在线车部分,立即执行 */
    BATCH_EAGER_ONLINE,

    /** pending_online → connected 回放,走 jitter 分桶 */
    OFFLINE_REPLAY,

    /** >10000 批量任务全量,走 jitter 分桶 */
    BATCH_THROTTLED;

    /** 是否需要 jitter 分桶延迟 */
    public boolean requiresJitter() {
        return this == OFFLINE_REPLAY || this == BATCH_THROTTLED;
    }
}

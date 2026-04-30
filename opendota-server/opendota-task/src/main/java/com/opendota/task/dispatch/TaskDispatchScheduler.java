package com.opendota.task.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 分桶 jitter 调度器(架构 §4.4.4, v1.4 A1/B2 修正)。
 *
 * <p>v1.4 决策:jitter 仅适用于 {@link DispatchSource#OFFLINE_REPLAY} 和
 * {@link DispatchSource#BATCH_THROTTLED}。在线车首次分发({@code ONLINE_IMMEDIATE} /
 * {@code BATCH_EAGER_ONLINE}) 立即执行,保障 SLO。
 *
 * <p>分桶算法: {@code bucket = abs(vin.hashCode()) % bucketCount},
 * 基准延迟 = bucket * (jitterMaxMs / bucketCount),再叠加 [0, bucketWidth) 随机抖动。
 * 这样同一 VIN 始终落入同一桶,保证顺序性;不同 VIN 均匀分散。
 */
@Component
public class TaskDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatchScheduler.class);

    private final int bucketCount;
    private final long jitterMaxMs;
    private final long bucketWidthMs;
    private final ScheduledExecutorService scheduler;

    public TaskDispatchScheduler(DispatchProperties props) {
        this(props.getBucketCount(), props.getJitterMaxMs());
    }

    // 测试可见的构造器
    TaskDispatchScheduler(int bucketCount, long jitterMaxMs) {
        this.bucketCount = bucketCount;
        this.jitterMaxMs = jitterMaxMs;
        this.bucketWidthMs = jitterMaxMs / bucketCount;
        this.scheduler = Executors.newScheduledThreadPool(
                Runtime.getRuntime().availableProcessors(),
                r -> {
                    Thread t = new Thread(r, "dispatch-scheduler");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * 按来源调度分发任务。
     *
     * @param vin    车架号,用于分桶
     * @param task   实际分发逻辑(MQTT publish 等)
     * @param source 分发来源
     */
    public void schedule(String vin, Runnable task, DispatchSource source) {
        if (source.requiresJitter()) {
            long delay = computeDelay(vin);
            log.debug("jitter 调度 vin={} source={} delayMs={}", vin, source, delay);
            scheduler.schedule(task, delay, TimeUnit.MILLISECONDS);
        } else {
            // 立即在调度线程执行;若需异步可改为 scheduler.execute(task)
            task.run();
        }
    }

    /**
     * 计算 jitter 延迟:base + random,范围 [0, jitterMaxMs)。
     */
    long computeDelay(String vin) {
        int bucket = Math.abs(vin.hashCode()) % bucketCount;
        long base = bucket * bucketWidthMs;
        long jitter = (long) (Math.random() * bucketWidthMs);
        return base + jitter;
    }

    /** 关闭调度器(应用退出时调用) */
    public void shutdown() {
        scheduler.shutdown();
    }
}

package com.opendota.task.dispatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskDispatchScheduler 单元测试。
 */
class TaskDispatchSchedulerTest {

    private TaskDispatchScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new TaskDispatchScheduler(60, 30_000);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void schedule_onlineImmediate_executesImmediately() throws InterruptedException {
        AtomicBoolean executed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        scheduler.schedule("LSVWA234567890123", () -> {
            executed.set(true);
            latch.countDown();
        }, DispatchSource.ONLINE_IMMEDIATE);

        assertTrue(latch.await(100, TimeUnit.MILLISECONDS), "ONLINE_IMMEDIATE 应立即执行");
        assertTrue(executed.get());
    }

    @Test
    void schedule_batchEagerOnline_executesImmediately() throws InterruptedException {
        AtomicBoolean executed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        scheduler.schedule("LSVWA234567890123", () -> {
            executed.set(true);
            latch.countDown();
        }, DispatchSource.BATCH_EAGER_ONLINE);

        assertTrue(latch.await(100, TimeUnit.MILLISECONDS), "BATCH_EAGER_ONLINE 应立即执行");
        assertTrue(executed.get());
    }

    @Test
    void schedule_offlineReplay_delaysExecution() throws InterruptedException {
        AtomicBoolean executed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        long start = System.nanoTime();
        scheduler.schedule("LSVWA234567890123", () -> {
            executed.set(true);
            latch.countDown();
        }, DispatchSource.OFFLINE_REPLAY);

        // 不应该立即执行
        assertFalse(executed.get(), "OFFLINE_REPLAY 不应立即执行");

        // 在 jitterMaxMs 内应执行
        assertTrue(latch.await(31, TimeUnit.SECONDS), "应在 jitter 窗口内执行");
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsed > 0, "应有延迟");
    }

    @Test
    void schedule_batchThrottled_delaysExecution() throws InterruptedException {
        AtomicBoolean executed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        scheduler.schedule("LSVWA234567890123", () -> {
            executed.set(true);
            latch.countDown();
        }, DispatchSource.BATCH_THROTTLED);

        assertFalse(executed.get(), "BATCH_THROTTLED 不应立即执行");
        assertTrue(latch.await(31, TimeUnit.SECONDS));
    }

    @Test
    void computeDelay_sameVIn_sameBucket() {
        // 同一 VIN 应落入同一桶,基准延迟一致
        String vin = "LSVWA234567890123";
        long delay1 = scheduler.computeDelay(vin);
        long delay2 = scheduler.computeDelay(vin);

        // 基准部分相同(bucket * bucketWidth),随机部分可能不同
        // 所以延迟差距应小于一个桶宽度
        long bucketWidth = 30_000 / 60; // 500ms
        assertTrue(Math.abs(delay1 - delay2) < bucketWidth,
                "同一 VIN 的延迟差距应小于一个桶宽度");
    }

    @Test
    void computeDelay_inRange() {
        // 多次计算应始终在 [0, jitterMaxMs) 范围内
        for (int i = 0; i < 100; i++) {
            long delay = scheduler.computeDelay("VIN" + i);
            assertTrue(delay >= 0, "延迟应 >= 0");
            assertTrue(delay < 30_000, "延迟应 < jitterMaxMs");
        }
    }

    @Test
    void computeDelay_differentVIns_spreadAcrossBuckets() {
        // 不同 VIN 应分散到不同桶
        AtomicInteger uniqueBuckets = new AtomicInteger(0);
        boolean[] seen = new boolean[60];

        for (int i = 0; i < 1000; i++) {
            String vin = String.format("LSVWA%012d", i);
            int bucket = Math.abs(vin.hashCode()) % 60;
            if (!seen[bucket]) {
                seen[bucket] = true;
                uniqueBuckets.incrementAndGet();
            }
        }

        // 1000 个不同 VIN 应覆盖大部分桶
        assertTrue(uniqueBuckets.get() > 40,
                "1000 个 VIN 应覆盖大部分桶,实际: " + uniqueBuckets.get());
    }
}

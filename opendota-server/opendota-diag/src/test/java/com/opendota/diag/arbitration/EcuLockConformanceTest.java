package com.opendota.diag.arbitration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 C 组符合性测试(协议附录 D)。
 *
 * <p>验证云端 ECU 锁机制的正确性:
 * <ul>
 *   <li>C-1: 跨车环形死锁防护 — 两线程反序 ecuScope 并发获取,字典序保证无死锁</li>
 *   <li>C-2: 部分 ECU 被占用时全量回滚 — all-or-nothing 语义</li>
 * </ul>
 *
 * <p>纯单元测试,不需要 MQTT / Redis 等基础设施。
 */
@Tag("conformance")
@ExtendWith(MockitoExtension.class)
@DisplayName("EcuLock 符合性测试 — 协议附录 D C 组")
class EcuLockConformanceTest {

    private static final String VIN = "LSVWA234567890123";

    @Mock
    private RedissonClient redissonClient;

    private EcuLockRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new EcuLockRegistry(redissonClient);
    }

    /**
     * C-1: 跨车环形死锁防护。
     *
     * <p>两个线程同时请求同 VIN 的 ecuScope,但顺序相反:
     * <ul>
     *   <li>线程 1: ["BMS", "VCU"]</li>
     *   <li>线程 2: ["VCU", "BMS"]</li>
     * </ul>
     *
     * <p>字典序排序保证两个线程都按 [BMS → VCU] 顺序获取锁,
     * 消除环形等待条件,无死锁。
     */
    @Test
    @DisplayName("C-1: 反序 ecuScope 并发获取 — 字典序防死锁")
    void crossVehicleDeadlockPrevention() throws Exception {
        int threadCount = 2;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<List<String>>> futures = new ArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        try {
            // 线程 1: ["BMS", "VCU"]
            futures.add(executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                RedissonClient localRedisson = org.mockito.Mockito.mock(RedissonClient.class);
                RLock lockBms = org.mockito.Mockito.mock(RLock.class);
                RLock lockVcu = org.mockito.Mockito.mock(RLock.class);

                when(localRedisson.getLock("ecu:lock:" + VIN + ":BMS")).thenReturn(lockBms);
                when(localRedisson.getLock("ecu:lock:" + VIN + ":VCU")).thenReturn(lockVcu);
                when(lockBms.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(true);
                when(lockVcu.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(true);

                EcuLockRegistry localRegistry = new EcuLockRegistry(localRedisson);
                List<EcuLockRegistry.AcquiredLock> locks =
                        localRegistry.tryAcquireAll(VIN, List.of("BMS", "VCU"));

                List<String> order = locks.stream()
                        .map(EcuLockRegistry.AcquiredLock::ecuName)
                        .toList();
                localRegistry.releaseAll(locks);
                return order;
            }));

            // 线程 2: ["VCU", "BMS"] (反序)
            futures.add(executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                RedissonClient localRedisson = org.mockito.Mockito.mock(RedissonClient.class);
                RLock lockBms = org.mockito.Mockito.mock(RLock.class);
                RLock lockVcu = org.mockito.Mockito.mock(RLock.class);

                when(localRedisson.getLock("ecu:lock:" + VIN + ":BMS")).thenReturn(lockBms);
                when(localRedisson.getLock("ecu:lock:" + VIN + ":VCU")).thenReturn(lockVcu);
                when(lockBms.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(true);
                when(lockVcu.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(true);

                EcuLockRegistry localRegistry = new EcuLockRegistry(localRedisson);
                List<EcuLockRegistry.AcquiredLock> locks =
                        localRegistry.tryAcquireAll(VIN, List.of("VCU", "BMS"));

                List<String> order = locks.stream()
                        .map(EcuLockRegistry.AcquiredLock::ecuName)
                        .toList();
                localRegistry.releaseAll(locks);
                return order;
            }));

            // 两线程均在 10 秒内完成(无死锁)
            for (Future<List<String>> f : futures) {
                List<String> order = f.get(10, TimeUnit.SECONDS);
                // 两个线程获取顺序均应为字典序 [BMS, VCU]
                assertThat(order).containsExactly("BMS", "VCU");
            }
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * C-2: 部分 ECU 被占用时全量回滚。
     *
     * <p>ecuScope ["BMS", "VCU"] 中 BMS 锁被占用(tryLock 返回 false),
     * VCU 锁获取成功后应被自动释放(all-or-nothing)。
     */
    @Test
    @DisplayName("C-2: 部分锁获取失败 — 已获取的锁全部回滚")
    void partialOccupancyRollback() throws Exception {
        // 按字典序:先尝试 BMS(失败),VCU 不会被尝试
        RLock rLockBms = org.mockito.Mockito.mock(RLock.class);

        when(redissonClient.getLock("ecu:lock:" + VIN + ":BMS")).thenReturn(rLockBms);
        when(rLockBms.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(false);

        assertThatThrownBy(() -> registry.tryAcquireAll(VIN, List.of("BMS", "VCU")))
                .isInstanceOf(EcuLockException.class)
                .satisfies(ex -> {
                    EcuLockException lockEx = (EcuLockException) ex;
                    assertThat(lockEx.getVin()).isEqualTo(VIN);
                    // 第一把锁(BMS)失败,conflictEcus 应包含 BMS
                    assertThat(lockEx.getConflictEcus()).contains("BMS");
                });

        // BMS 未被获取,不需要释放
        verify(rLockBms, never()).unlock();
    }

    /**
     * C-2 补充:三 ECU 场景,中间 ECU 失败时前一把已获取的锁需回滚。
     */
    @Test
    @DisplayName("C-2 补充:三 ECU 中间失败 — 前面已获取的锁全部回滚")
    void partialOccupancyThreeEcusRollback() throws Exception {
        RLock rLockBms = org.mockito.Mockito.mock(RLock.class);
        RLock rLockGw = org.mockito.Mockito.mock(RLock.class);

        when(redissonClient.getLock("ecu:lock:" + VIN + ":BMS")).thenReturn(rLockBms);
        when(redissonClient.getLock("ecu:lock:" + VIN + ":GW")).thenReturn(rLockGw);
        // 字典序:BMS → GW → VCU;GW 失败时 VCU 不会被尝试
        when(rLockBms.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(rLockGw.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(false);
        when(rLockBms.isHeldByCurrentThread()).thenReturn(true);

        // ecuScope 乱序传入,验证字典序排序后 BMS 先获取成功,GW 失败触发回滚
        assertThatThrownBy(() -> registry.tryAcquireAll(VIN, List.of("VCU", "BMS", "GW")))
                .isInstanceOf(EcuLockException.class)
                .satisfies(ex -> {
                    EcuLockException lockEx = (EcuLockException) ex;
                    assertThat(lockEx.getVin()).isEqualTo(VIN);
                    assertThat(lockEx.getConflictEcus()).contains("GW");
                });

        // BMS 已获取 → 被释放(反序回滚)
        verify(rLockBms).unlock();
        // GW 未获取成功 → 不释放
        verify(rLockGw, never()).unlock();
    }
}

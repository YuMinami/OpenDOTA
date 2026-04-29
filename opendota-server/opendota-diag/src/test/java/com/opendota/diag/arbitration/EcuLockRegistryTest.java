package com.opendota.diag.arbitration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EcuLockRegistry — per-ECU 分布式锁注册表")
class EcuLockRegistryTest {

    private static final String VIN = "LSVWA234567890123";

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLockBms;

    @Mock
    private RLock rLockVcu;

    private EcuLockRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new EcuLockRegistry(redissonClient);
    }

    @Nested
    @DisplayName("tryAcquireAll — 成功场景")
    class TryAcquireAllSuccess {

        @Test
        @DisplayName("单 ECU 锁获取成功")
        void singleEcu() throws Exception {
            when(redissonClient.getLock("ecu:lock:" + VIN + ":VCU")).thenReturn(rLockVcu);
            when(rLockVcu.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(true);

            List<EcuLockRegistry.AcquiredLock> locks = registry.tryAcquireAll(VIN, List.of("VCU"));

            assertThat(locks).hasSize(1);
            assertThat(locks.getFirst().ecuName()).isEqualTo("VCU");
            verify(rLockVcu).tryLock(5L, 0L, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("多 ECU:按字典序获取,BMS → VCU")
        void multiEcuOrdered() throws Exception {
            when(redissonClient.getLock("ecu:lock:" + VIN + ":BMS")).thenReturn(rLockBms);
            when(redissonClient.getLock("ecu:lock:" + VIN + ":VCU")).thenReturn(rLockVcu);
            when(rLockBms.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(true);
            when(rLockVcu.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(true);

            // 输入乱序 [VCU, BMS],期望按字典序 [BMS, VCU] 获取
            List<EcuLockRegistry.AcquiredLock> locks = registry.tryAcquireAll(VIN, List.of("VCU", "BMS"));

            assertThat(locks).hasSize(2);
            assertThat(locks).extracting(EcuLockRegistry.AcquiredLock::ecuName)
                    .containsExactly("BMS", "VCU");

            // 验证 BMS 先获取
            var inOrder = org.mockito.Mockito.inOrder(rLockBms, rLockVcu);
            inOrder.verify(rLockBms).tryLock(5L, 0L, TimeUnit.SECONDS);
            inOrder.verify(rLockVcu).tryLock(5L, 0L, TimeUnit.SECONDS);
        }
    }

    @Nested
    @DisplayName("tryAcquireAll — 失败回滚场景")
    class TryAcquireAllFailure {

        @Test
        @DisplayName("第二把锁获取失败:已获取的第一把锁被释放")
        void secondLockFailsReleasesFirst() throws Exception {
            when(redissonClient.getLock("ecu:lock:" + VIN + ":BMS")).thenReturn(rLockBms);
            when(redissonClient.getLock("ecu:lock:" + VIN + ":VCU")).thenReturn(rLockVcu);
            when(rLockBms.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(true);
            when(rLockVcu.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(false);
            when(rLockBms.isHeldByCurrentThread()).thenReturn(true);

            assertThatThrownBy(() -> registry.tryAcquireAll(VIN, List.of("VCU", "BMS")))
                    .isInstanceOf(EcuLockException.class)
                    .satisfies(ex -> {
                        EcuLockException lockEx = (EcuLockException) ex;
                        assertThat(lockEx.getVin()).isEqualTo(VIN);
                        assertThat(lockEx.getConflictEcus()).contains("VCU");
                    });

            // BMS 锁被释放
            verify(rLockBms).unlock();
            // VCU 锁未被获取,不需要释放
            verify(rLockVcu, never()).unlock();
        }

        @Test
        @DisplayName("第一把锁获取失败:无需释放")
        void firstLockFails() throws Exception {
            when(redissonClient.getLock("ecu:lock:" + VIN + ":BMS")).thenReturn(rLockBms);
            when(rLockBms.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(false);

            assertThatThrownBy(() -> registry.tryAcquireAll(VIN, List.of("BMS", "VCU")))
                    .isInstanceOf(EcuLockException.class)
                    .satisfies(ex -> {
                        EcuLockException lockEx = (EcuLockException) ex;
                        assertThat(lockEx.getConflictEcus()).contains("BMS", "VCU");
                    });

            verify(rLockBms, never()).unlock();
        }

        @Test
        @DisplayName("三把锁中间失败:前两把被释放")
        void threeLocksMiddleFails() throws Exception {
            RLock rLockGw = org.mockito.Mockito.mock(RLock.class);

            when(redissonClient.getLock("ecu:lock:" + VIN + ":BMS")).thenReturn(rLockBms);
            when(redissonClient.getLock("ecu:lock:" + VIN + ":GW")).thenReturn(rLockGw);
            when(redissonClient.getLock("ecu:lock:" + VIN + ":VCU")).thenReturn(rLockVcu);
            when(rLockBms.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(true);
            when(rLockGw.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(true);
            when(rLockVcu.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(false);
            when(rLockBms.isHeldByCurrentThread()).thenReturn(true);
            when(rLockGw.isHeldByCurrentThread()).thenReturn(true);

            assertThatThrownBy(() -> registry.tryAcquireAll(VIN, List.of("VCU", "BMS", "GW")))
                    .isInstanceOf(EcuLockException.class);

            // 反序释放:先 GW 后 BMS
            var inOrder = org.mockito.Mockito.inOrder(rLockGw, rLockBms);
            inOrder.verify(rLockGw).unlock();
            inOrder.verify(rLockBms).unlock();
            verify(rLockVcu, never()).unlock();
        }
    }

    @Nested
    @DisplayName("releaseAll — 反序释放")
    class ReleaseAll {

        @Test
        @DisplayName("反序释放:最后获取的先释放")
        void reverseOrder() throws Exception {
            when(redissonClient.getLock(anyString())).thenReturn(rLockBms, rLockVcu);
            when(rLockBms.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(true);
            when(rLockVcu.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(true);
            when(rLockBms.isHeldByCurrentThread()).thenReturn(true);
            when(rLockVcu.isHeldByCurrentThread()).thenReturn(true);

            List<EcuLockRegistry.AcquiredLock> locks = registry.tryAcquireAll(VIN, List.of("BMS", "VCU"));

            registry.releaseAll(locks);

            // 反序:VCU 先释放,BMS 后释放
            var inOrder = org.mockito.Mockito.inOrder(rLockVcu, rLockBms);
            inOrder.verify(rLockVcu).unlock();
            inOrder.verify(rLockBms).unlock();
        }

        @Test
        @DisplayName("空列表:不抛异常")
        void emptyList() {
            registry.releaseAll(List.of());
            // 无异常
        }

        @Test
        @DisplayName("null:不抛异常")
        void nullList() {
            registry.releaseAll(null);
            // 无异常
        }

        @Test
        @DisplayName("单个释放失败不影响其余")
        void oneReleaseFailsOthersStillReleased() throws Exception {
            RLock rLockGw = org.mockito.Mockito.mock(RLock.class);

            when(redissonClient.getLock(anyString())).thenReturn(rLockBms, rLockGw, rLockVcu);
            when(rLockBms.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(true);
            when(rLockGw.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(true);
            when(rLockVcu.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(true);
            when(rLockBms.isHeldByCurrentThread()).thenReturn(true);
            when(rLockGw.isHeldByCurrentThread()).thenReturn(true);
            when(rLockVcu.isHeldByCurrentThread()).thenReturn(true);

            // GW 的 unlock 抛异常
            doAnswer(inv -> { throw new RuntimeException("unlock failed"); }).when(rLockGw).unlock();

            List<EcuLockRegistry.AcquiredLock> locks = registry.tryAcquireAll(VIN, List.of("BMS", "GW", "VCU"));

            // 不抛异常
            registry.releaseAll(locks);

            // VCU 和 BMS 仍然被释放
            verify(rLockVcu).unlock();
            verify(rLockBms).unlock();
        }
    }

    @Nested
    @DisplayName("并发场景 — 无死锁")
    class ConcurrencyTest {

        @Test
        @DisplayName("10 个跨 ECU 并发请求:按字典序获取,无死锁")
        void tenConcurrentRequestsNoDeadlock() throws Exception {
            // 使用简单的计数锁模拟:允许前 N 次获取成功
            // 这里验证的是:所有请求都按相同字典序获取,不会出现环形等待
            int threadCount = 10;
            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            CountDownLatch done = new CountDownLatch(threadCount);
            List<Future<Boolean>> futures = new ArrayList<>();

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int i = 0; i < threadCount; i++) {
                // 交替使用不同的 ecuScope 顺序
                List<String> ecuScope = i % 2 == 0
                        ? List.of("VCU", "BMS")
                        : List.of("BMS", "VCU");

                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        // 创建独立的 mock(每个线程有自己的锁状态)
                        RedissonClient localRedisson = org.mockito.Mockito.mock(RedissonClient.class);
                        RLock localLockBms = org.mockito.Mockito.mock(RLock.class);
                        RLock localLockVcu = org.mockito.Mockito.mock(RLock.class);

                        when(localRedisson.getLock("ecu:lock:" + VIN + ":BMS")).thenReturn(localLockBms);
                        when(localRedisson.getLock("ecu:lock:" + VIN + ":VCU")).thenReturn(localLockVcu);
                        when(localLockBms.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(true);
                        when(localLockVcu.tryLock(eq(5L), eq(0L), eq(TimeUnit.SECONDS))).thenReturn(true);
                        when(localLockBms.isHeldByCurrentThread()).thenReturn(true);
                        when(localLockVcu.isHeldByCurrentThread()).thenReturn(true);

                        EcuLockRegistry localRegistry = new EcuLockRegistry(localRedisson);
                        List<EcuLockRegistry.AcquiredLock> locks = localRegistry.tryAcquireAll(VIN, ecuScope);

                        // 验证获取顺序始终是 BMS → VCU
                        assertThat(locks).extracting(EcuLockRegistry.AcquiredLock::ecuName)
                                .containsExactly("BMS", "VCU");

                        localRegistry.releaseAll(locks);
                        return true;
                    } finally {
                        done.countDown();
                    }
                }));
            }

            // 所有线程在 10 秒内完成
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            for (Future<Boolean> f : futures) {
                assertThat(f.get()).isTrue();
            }

            executor.shutdown();
        }
    }

    @Nested
    @DisplayName("EcuLockException")
    class EcuLockExceptionTest {

        @Test
        @DisplayName("携带 conflictEcus 和 vin")
        void carriesConflictEcus() {
            EcuLockException ex = new EcuLockException(VIN, List.of("BMS"), "锁获取失败");
            assertThat(ex.getVin()).isEqualTo(VIN);
            assertThat(ex.getConflictEcus()).containsExactly("BMS");
            assertThat(ex.code()).isEqualTo(42307);
        }

        @Test
        @DisplayName("多个 conflictEcus")
        void multipleConflictEcus() {
            EcuLockException ex = new EcuLockException(VIN, List.of("BMS", "GW"), "锁获取失败");
            assertThat(ex.getConflictEcus()).containsExactly("BMS", "GW");
        }
    }
}

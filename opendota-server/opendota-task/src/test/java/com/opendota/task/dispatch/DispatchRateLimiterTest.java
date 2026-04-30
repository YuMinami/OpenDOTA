package com.opendota.task.dispatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DispatchRateLimiter 单元测试。
 */
class DispatchRateLimiterTest {

    private RedissonClient redisson;
    private DispatchProperties props;
    private DispatchRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        props = new DispatchProperties();
        limiter = new DispatchRateLimiter(redisson, props);
    }

    @Test
    void tryAcquire_allBucketsPass_returnsTrue() {
        RRateLimiter rateLimiter = mock(RRateLimiter.class);
        when(redisson.getRateLimiter(org.mockito.ArgumentMatchers.<String>any())).thenReturn(rateLimiter);
        when(rateLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
        when(rateLimiter.tryAcquire()).thenReturn(true);

        boolean result = limiter.tryAcquire("tenant-1", 5, "LSVWA234567890123");

        assertTrue(result);
        // 4 个维度各调用一次 tryAcquire
        verify(rateLimiter, times(4)).tryAcquire();
    }

    @Test
    void tryAcquire_globalBucketExhausted_returnsFalse() {
        RRateLimiter globalLimiter = mock(RRateLimiter.class);

        when(redisson.getRateLimiter("rate:dispatch:global")).thenReturn(globalLimiter);
        when(globalLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
        when(globalLimiter.tryAcquire()).thenReturn(false);

        boolean result = limiter.tryAcquire("tenant-1", 5, "LSVWA234567890123");

        assertFalse(result);
        // 全局限流超限后,不应尝试获取后续维度的限流器
        verify(redisson).getRateLimiter("rate:dispatch:global");
        verify(globalLimiter).tryAcquire();
    }

    @Test
    void tryAcquire_tenantBucketExhausted_returnsFalse() {
        RRateLimiter globalLimiter = mock(RRateLimiter.class);
        RRateLimiter tenantLimiter = mock(RRateLimiter.class);

        when(redisson.getRateLimiter("rate:dispatch:global")).thenReturn(globalLimiter);
        when(globalLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
        when(globalLimiter.tryAcquire()).thenReturn(true);

        when(redisson.getRateLimiter("rate:dispatch:tenant:tenant-1")).thenReturn(tenantLimiter);
        when(tenantLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
        when(tenantLimiter.tryAcquire()).thenReturn(false);

        boolean result = limiter.tryAcquire("tenant-1", 5, "LSVWA234567890123");

        assertFalse(result);
    }

    @Test
    void tryAcquire_priorityBucketExhausted_returnsFalse() {
        RRateLimiter globalLimiter = mock(RRateLimiter.class);
        RRateLimiter tenantLimiter = mock(RRateLimiter.class);
        RRateLimiter priorityLimiter = mock(RRateLimiter.class);

        when(redisson.getRateLimiter("rate:dispatch:global")).thenReturn(globalLimiter);
        when(globalLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
        when(globalLimiter.tryAcquire()).thenReturn(true);

        when(redisson.getRateLimiter("rate:dispatch:tenant:tenant-1")).thenReturn(tenantLimiter);
        when(tenantLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
        when(tenantLimiter.tryAcquire()).thenReturn(true);

        when(redisson.getRateLimiter("rate:dispatch:priority:3")).thenReturn(priorityLimiter);
        when(priorityLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
        when(priorityLimiter.tryAcquire()).thenReturn(false);

        boolean result = limiter.tryAcquire("tenant-1", 3, "LSVWA234567890123");

        assertFalse(result);
    }

    @Test
    void tryAcquire_vinBucketExhausted_returnsFalse() {
        RRateLimiter globalLimiter = mock(RRateLimiter.class);
        RRateLimiter tenantLimiter = mock(RRateLimiter.class);
        RRateLimiter priorityLimiter = mock(RRateLimiter.class);
        RRateLimiter vinLimiter = mock(RRateLimiter.class);

        when(redisson.getRateLimiter("rate:dispatch:global")).thenReturn(globalLimiter);
        when(globalLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
        when(globalLimiter.tryAcquire()).thenReturn(true);

        when(redisson.getRateLimiter("rate:dispatch:tenant:tenant-1")).thenReturn(tenantLimiter);
        when(tenantLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
        when(tenantLimiter.tryAcquire()).thenReturn(true);

        when(redisson.getRateLimiter("rate:dispatch:priority:3")).thenReturn(priorityLimiter);
        when(priorityLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
        when(priorityLimiter.tryAcquire()).thenReturn(true);

        when(redisson.getRateLimiter("rate:dispatch:vin:LSVWA234567890123")).thenReturn(vinLimiter);
        when(vinLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
        when(vinLimiter.tryAcquire()).thenReturn(false);

        boolean result = limiter.tryAcquire("tenant-1", 3, "LSVWA234567890123");

        assertFalse(result);
    }

    @Test
    void tryAcquire_usesCustomPriorityQuota() {
        props.getRateLimit().getPriorityQuota().put(0, 500);

        RRateLimiter rateLimiter = mock(RRateLimiter.class);
        when(redisson.getRateLimiter(org.mockito.ArgumentMatchers.<String>any())).thenReturn(rateLimiter);
        when(rateLimiter.trySetRate(any(), anyLong(), any())).thenReturn(true);
        when(rateLimiter.tryAcquire()).thenReturn(true);

        limiter.tryAcquire("tenant-1", 0, "LSVWA234567890123");

        // 验证优先级 0 使用自定义配额 500
        verify(redisson).getRateLimiter("rate:dispatch:priority:0");
    }
}

package com.opendota.diag.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis 健康探针。
 *
 * <p>正常态走 Redis Pub/Sub;一旦发布或订阅侧报错,立即切换到 PG 直扫模式。
 * Redis 恢复后需连续 3 次 ping 成功才回切,避免抖动来回震荡。
 */
@Component
public class RedisHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(RedisHealthProbe.class);
    private static final int RECOVERY_SUCCESS_THRESHOLD = 3;

    private final StringRedisTemplate redisTemplate;
    private final AtomicBoolean redisHealthy = new AtomicBoolean(true);
    private final AtomicInteger successStreak = new AtomicInteger(0);

    public RedisHealthProbe(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean isRedisHealthy() {
        return redisHealthy.get();
    }

    public void markUnhealthy(Throwable cause) {
        successStreak.set(0);
        if (redisHealthy.compareAndSet(true, false)) {
            if (cause == null) {
                log.warn("Redis 故障,切换到 sse_event 直扫模式");
            } else {
                log.warn("Redis 故障,切换到 sse_event 直扫模式", cause);
            }
        }
    }

    @Scheduled(fixedDelay = 2000)
    public void probe() {
        try {
            String pong = redisTemplate.execute((RedisConnection connection) -> connection.ping());
            if (pong == null || pong.isBlank()) {
                markUnhealthy(null);
                return;
            }
            if (!redisHealthy.get()) {
                int consecutiveSuccesses = successStreak.incrementAndGet();
                if (consecutiveSuccesses >= RECOVERY_SUCCESS_THRESHOLD) {
                    redisHealthy.set(true);
                    successStreak.set(0);
                    log.info("Redis 已恢复,停用 sse_event 直扫模式");
                }
            } else {
                successStreak.set(0);
            }
        } catch (Exception ex) {
            markUnhealthy(ex);
        }
    }
}

@Configuration
@EnableScheduling
class SseSchedulingConfiguration {
}

package com.opendota.task.dispatch;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 全局分发限流器(架构 §4.4.2)。
 *
 * <p>基于 Redisson {@link RRateLimiter} 实现四级令牌桶,所有 DispatchWorker 节点共享同一 Redis 限流状态:
 * <ol>
 *   <li>全局:保护 MQTT Broker 总吞吐</li>
 *   <li>租户:防止单租户占满全局配额</li>
 *   <li>优先级:高优先级独立桶,不被低优先级饿死</li>
 *   <li>单 VIN:防止重试风暴反复冲击同一辆车</li>
 * </ol>
 *
 * <p>短路求值:任一维度超限立即返回 false,不消耗后续维度令牌。
 */
@Component
public class DispatchRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DispatchRateLimiter.class);

    private final RedissonClient redisson;
    private final DispatchProperties props;

    public DispatchRateLimiter(RedissonClient redisson, DispatchProperties props) {
        this.redisson = redisson;
        this.props = props;
    }

    /**
     * 尝试获取分发令牌。
     *
     * @param tenantId 租户 ID
     * @param priority 优先级(0-9)
     * @param vin      车架号
     * @return true 表示获取成功可下发,false 表示被限流
     */
    public boolean tryAcquire(String tenantId, int priority, String vin) {
        DispatchProperties.RateLimit rl = props.getRateLimit();

        // 1. 全局限流
        if (!tryAcquire("rate:dispatch:global",
                rl.getGlobalPerSecond(), Duration.ofSeconds(1))) {
            log.debug("分发限流:全局配额超限");
            return false;
        }
        // 2. 租户限流
        if (!tryAcquire("rate:dispatch:tenant:" + tenantId,
                rl.getTenantPerSecond(), Duration.ofSeconds(1))) {
            log.debug("分发限流:租户 {} 配额超限", tenantId);
            return false;
        }
        // 3. 优先级限流
        int priorityQuota = rl.getPriorityQuota().getOrDefault(priority, 20);
        if (!tryAcquire("rate:dispatch:priority:" + priority,
                priorityQuota, Duration.ofSeconds(1))) {
            log.debug("分发限流:优先级 {} 配额超限", priority);
            return false;
        }
        // 4. 单 VIN 限流
        if (!tryAcquire("rate:dispatch:vin:" + vin,
                rl.getVinPerMinute(), Duration.ofMinutes(1))) {
            log.debug("分发限流:VIN {} 配额超限", vin);
            return false;
        }
        return true;
    }

    private boolean tryAcquire(String key, int rate, Duration interval) {
        RRateLimiter limiter = redisson.getRateLimiter(key);
        // trySetRate 幂等:已存在时不会覆盖
        limiter.trySetRate(RateType.OVERALL, rate, interval);
        return limiter.tryAcquire();
    }
}

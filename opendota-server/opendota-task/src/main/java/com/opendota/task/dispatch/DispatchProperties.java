package com.opendota.task.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 分发调度器配置(架构 §4.4)。
 *
 * <p>从 {@code opendota.dispatch.*} 读取,覆盖 jitter 分桶、限流配额、重试策略。
 */
@Component
@ConfigurationProperties(prefix = "opendota.dispatch")
public class DispatchProperties {

    /** jitter 分桶数(架构 §4.4.4),默认 60 */
    private int bucketCount = 60;

    /** 最大 jitter 窗口(ms),默认 30000 */
    private long jitterMaxMs = 30_000;

    /** 最大重试次数,超限进 DLQ,默认 5 */
    private int maxRetries = 5;

    private final RateLimit rateLimit = new RateLimit();

    public int getBucketCount() { return bucketCount; }
    public void setBucketCount(int bucketCount) { this.bucketCount = bucketCount; }

    public long getJitterMaxMs() { return jitterMaxMs; }
    public void setJitterMaxMs(long jitterMaxMs) { this.jitterMaxMs = jitterMaxMs; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public RateLimit getRateLimit() { return rateLimit; }

    /**
     * 限流配额配置(架构 §4.4.2)。
     */
    public static class RateLimit {
        /** 全局每秒配额,默认 500 */
        private int globalPerSecond = 500;
        /** 租户每秒配额,默认 100 */
        private int tenantPerSecond = 100;
        /** 单 VIN 每分钟配额,默认 10 */
        private int vinPerMinute = 10;
        /** 优先级独立配额,key 为 priority(0-9),value 为每秒配额 */
        private Map<Integer, Integer> priorityQuota = defaultPriorityQuota();

        public int getGlobalPerSecond() { return globalPerSecond; }
        public void setGlobalPerSecond(int v) { this.globalPerSecond = v; }

        public int getTenantPerSecond() { return tenantPerSecond; }
        public void setTenantPerSecond(int v) { this.tenantPerSecond = v; }

        public int getVinPerMinute() { return vinPerMinute; }
        public void setVinPerMinute(int v) { this.vinPerMinute = v; }

        public Map<Integer, Integer> getPriorityQuota() { return priorityQuota; }
        public void setPriorityQuota(Map<Integer, Integer> priorityQuota) { this.priorityQuota = priorityQuota; }

        private static Map<Integer, Integer> defaultPriorityQuota() {
            Map<Integer, Integer> m = new HashMap<>();
            m.put(0, 200); m.put(1, 150); m.put(2, 120); m.put(3, 100);
            m.put(4, 80);  m.put(5, 60);  m.put(6, 40);  m.put(7, 30);
            m.put(8, 20);  m.put(9, 20);
            return m;
        }
    }
}

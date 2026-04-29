package com.opendota.diag.arbitration;

import com.opendota.common.payload.common.EcuScope;

import java.util.List;
import java.util.Objects;

/**
 * ECU 锁获取的字典序策略(协议 §10.2.3)。
 *
 * <p>核心规则:
 * <ol>
 *   <li>对 {@code ecuScope} 中所有 ECU 名称按字典序升序排序</li>
 *   <li>按排序后的顺序逐个获取锁 —— 保证全局全序,消除环形等待死锁</li>
 *   <li>释放时按相反顺序(LIFO),便于日志对齐</li>
 * </ol>
 *
 * <p>锁 key 格式: {@code ecu:lock:{vin}:{ecuName}} —— 与车端 per-ECU 锁对应(双层)。
 */
public final class LockOrderPolicy {

    /** Redis key 前缀。 */
    public static final String LOCK_KEY_PREFIX = "ecu:lock:";

    private LockOrderPolicy() {
    }

    /**
     * 按字典序规范化 ECU scope 并生成有序的锁 key 列表。
     *
     * @param vin      车辆 VIN(17 位)
     * @param ecuScope ECU 名称列表(不能为空)
     * @return 按字典序排列的 {@link LockSpec} 列表
     * @throws IllegalArgumentException ecuScope 为空或 vin 非法
     */
    public static List<LockSpec> orderedLockSpecs(String vin, List<String> ecuScope) {
        Objects.requireNonNull(vin, "vin 必填");
        if (vin.isBlank()) {
            throw new IllegalArgumentException("vin 不能为空白");
        }
        List<String> normalized = EcuScope.normalize(ecuScope);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("ecuScope 必填(协议 v1.2 R2)");
        }
        return normalized.stream()
                .map(ecu -> new LockSpec(ecu, buildLockKey(vin, ecu)))
                .toList();
    }

    /**
     * 构建分布式锁的 Redis key。
     *
     * <p>格式:{@code ecu:lock:{vin}:{ecuName}} —— VIN 保证跨车隔离,ecuName 保证 per-ECU 粒度。
     */
    public static String buildLockKey(String vin, String ecuName) {
        return LOCK_KEY_PREFIX + vin + ":" + ecuName;
    }

    /**
     * 有序锁规格 —— ECU 名与其对应的 Redis 锁 key。
     *
     * @param ecuName ECU 名称(已规范化)
     * @param lockKey Redisson RLock 的 key
     */
    public record LockSpec(String ecuName, String lockKey) {
    }
}

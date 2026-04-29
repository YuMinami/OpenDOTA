package com.opendota.diag.arbitration;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redisson 的 per-ECU 分布式锁注册表(协议 §10.2.3)。
 *
 * <p>核心语义:
 * <ul>
 *   <li><b>字典序获取</b>:对 {@code ecuScope} 排序后逐个 {@code tryLock},保证全局全序,消除环形等待死锁</li>
 *   <li><b>全有或全无</b>:任一 ECU 获取失败,立即释放已获取的所有锁并抛 {@link EcuLockException}</li>
 *   <li><b>反序释放</b>:释放时按获取的相反顺序(LIFO),便于日志对齐</li>
 *   <li><b>云端↔车端双层</b>:锁 key 格式 {@code ecu:lock:{vin}:{ecuName}},与车端 per-ECU 状态机对应</li>
 * </ul>
 *
 * <p>超时:{@code tryLock} 默认 5 秒等待,0 秒 leaseTime(由 Redisson 看门狗自动续期)。
 *
 * <p>用法:
 * <pre>{@code
 *   List<EcuLockRegistry.AcquiredLock> locks = ecuLockRegistry.tryAcquireAll(vin, ecuScope);
 *   try {
 *       // ... 下发 MQTT ...
 *   } finally {
 *       ecuLockRegistry.releaseAll(locks);
 *   }
 * }</pre>
 */
@Component
public class EcuLockRegistry {

    private static final Logger log = LoggerFactory.getLogger(EcuLockRegistry.class);

    /** 默认锁等待超时(秒)。 */
    private static final long DEFAULT_WAIT_SECONDS = 5;

    private final RedissonClient redisson;

    public EcuLockRegistry(RedissonClient redisson) {
        this.redisson = redisson;
    }

    /**
     * 按字典序一次性原子获取所有 ECU 锁。
     *
     * <p>算法(协议 §10.2.3):
     * <ol>
     *   <li>对 {@code ecuScope} 按字典序升序排序</li>
     *   <li>逐个 {@code tryLock(wait=5s, lease=0)}</li>
     *   <li>任一失败 → 释放已获取的锁(反序) → 抛 {@link EcuLockException}</li>
     *   <li>全部成功 → 返回 {@link AcquiredLock} 列表(保持获取顺序)</li>
     * </ol>
     *
     * @param vin      车辆 VIN
     * @param ecuScope ECU 名称列表(内部会做字典序规范化)
     * @return 已获取的锁列表(按字典序排列),供 {@link #releaseAll} 使用
     * @throws EcuLockException 任一 ECU 锁获取失败
     * @throws IllegalArgumentException ecuScope 为空
     */
    public List<AcquiredLock> tryAcquireAll(String vin, List<String> ecuScope) {
        List<LockOrderPolicy.LockSpec> specs = LockOrderPolicy.orderedLockSpecs(vin, ecuScope);
        List<AcquiredLock> acquired = new ArrayList<>(specs.size());

        try {
            for (LockOrderPolicy.LockSpec spec : specs) {
                RLock lock = redisson.getLock(spec.lockKey());
                boolean locked = lock.tryLock(DEFAULT_WAIT_SECONDS, 0, TimeUnit.SECONDS);
                if (!locked) {
                    // 当前 ECU 被占用 → 回滚已获取的锁
                    List<String> conflictEcus = specs.stream()
                            .skip(acquired.size())
                            .map(LockOrderPolicy.LockSpec::ecuName)
                            .toList();
                    log.warn("ecuLock 获取失败 vin={} 冲突 ECU={} 已获取={}/{}",
                            vin, spec.ecuName(), acquired.size(), specs.size());
                    releaseAll(acquired);
                    throw new EcuLockException(vin, conflictEcus,
                            "ECU 锁获取失败:vin=" + vin + " 冲突 ECU=" + conflictEcus);
                }
                acquired.add(new AcquiredLock(spec.ecuName(), spec.lockKey(), lock));
                log.debug("ecuLock 已获取 vin={} ecu={} key={}", vin, spec.ecuName(), spec.lockKey());
            }

            log.info("ecuLock 全部获取成功 vin={} scope={} 共{}把锁", vin,
                    specs.stream().map(LockOrderPolicy.LockSpec::ecuName).toList(),
                    acquired.size());
            return Collections.unmodifiableList(acquired);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            releaseAll(acquired);
            throw new EcuLockException(vin,
                    specs.stream().map(LockOrderPolicy.LockSpec::ecuName).toList(),
                    "ECU 锁获取被中断:vin=" + vin);
        }
    }

    /**
     * 释放所有已获取的锁(反序 LIFO)。
     *
     * <p>按获取顺序的相反顺序释放,便于日志对齐。单个锁释放失败不影响其余锁的释放。
     *
     * @param acquiredLocks {@link #tryAcquireAll} 返回的锁列表
     */
    public void releaseAll(List<AcquiredLock> acquiredLocks) {
        if (acquiredLocks == null || acquiredLocks.isEmpty()) {
            return;
        }

        // 反序释放(LIFO)
        List<AcquiredLock> reversed = new ArrayList<>(acquiredLocks);
        Collections.reverse(reversed);

        for (AcquiredLock acquired : reversed) {
            try {
                if (acquired.lock().isHeldByCurrentThread()) {
                    acquired.lock().unlock();
                    log.debug("ecuLock 已释放 ecu={} key={}", acquired.ecuName(), acquired.lockKey());
                }
            } catch (Exception e) {
                // 单个锁释放失败不阻塞其余锁释放
                log.warn("ecuLock 释放异常 ecu={} key={}: {}",
                        acquired.ecuName(), acquired.lockKey(), e.getMessage());
            }
        }
    }

    /**
     * 查询指定 VIN + ECU 的锁是否已被当前线程持有。
     *
     * @param vin      车辆 VIN
     * @param ecuName  ECU 名称
     * @return true 如果当前线程持有该锁
     */
    public boolean isLockedByCurrentThread(String vin, String ecuName) {
        String lockKey = LockOrderPolicy.buildLockKey(vin, ecuName);
        RLock lock = redisson.getLock(lockKey);
        return lock.isHeldByCurrentThread();
    }

    /**
     * 已获取的锁记录。
     *
     * @param ecuName ECU 名称
     * @param lockKey Redisson 锁的 Redis key
     * @param lock    Redisson RLock 实例
     */
    public record AcquiredLock(String ecuName, String lockKey, RLock lock) {
    }
}

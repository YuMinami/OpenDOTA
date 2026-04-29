package com.opendota.diag.channel;

import com.opendota.common.envelope.Operator;
import com.opendota.diag.arbitration.EcuLockRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 诊断通道管理器(架构 §3.2 ChannelManager)。
 *
 * <p>职责(Phase 1 MVP):
 * <ol>
 *   <li>开通道:{@link #register} 登记 channelId → {@link ChannelContext}</li>
 *   <li>跟踪执行中的宏类型:{@link #markExecuting} / {@link #markIdle},供 v1.4 A2 判断</li>
 *   <li>关通道:{@link #close} 清理</li>
 * </ol>
 *
 * <p>**边界**:不负责 RBAC、抢占策略、pending queue —— 这些在后续 opendota-security /
 * opendota-task 里补齐。当前只做最小可用的通道生命周期记录。
 *
 * <p>**一致性**:进程内 {@link ConcurrentHashMap}。集群场景下每个节点各自持有一份,跨节点的通道统一
 * 由 PG {@code diag_channel_pending} + Redis Pub/Sub fanout 处理 —— Phase 2 接入。
 */
@Component
public class ChannelManager {

    private static final Logger log = LoggerFactory.getLogger(ChannelManager.class);

    private final Map<String, ChannelContext> channels = new ConcurrentHashMap<>();

    /** 通道关联的 ECU 分布式锁。key=channelId, value=锁列表。 */
    private final Map<String, List<EcuLockRegistry.AcquiredLock>> channelLocks = new ConcurrentHashMap<>();

    /** 登记一个新通道上下文。幂等:同 channelId 再次登记会覆盖,打 warn。 */
    public ChannelContext register(String channelId, String vin, String ecuName,
                                    List<String> ecuScope, Operator operator) {
        ChannelContext ctx = new ChannelContext(channelId, vin, ecuName, ecuScope, operator);
        ChannelContext previous = channels.put(channelId, ctx);
        if (previous != null) {
            log.warn("channel 重复登记 channelId={} vin={};覆盖旧上下文", channelId, vin);
        } else {
            log.info("channel 开通道 channelId={} vin={} ecu={} scope={} operator={}",
                    channelId, vin, ecuName, ctx.getEcuScope(),
                    operator == null ? "system" : operator.id());
        }
        return ctx;
    }

    public Optional<ChannelContext> get(String channelId) {
        return Optional.ofNullable(channels.get(channelId));
    }

    /**
     * 标记通道正在执行某个宏(供 A2 检查参考)。
     *
     * @param channelId 目标通道
     * @param taskId    关联的任务 / 脚本 / 单步 cmdId
     * @param macroType 宏类型字符串(如 {@code "macro_data_transfer"}),{@code null} 表示非宏(raw_uds 等)
     */
    public void markExecuting(String channelId, String taskId, String macroType) {
        ChannelContext ctx = channels.get(channelId);
        if (ctx == null) {
            log.debug("markExecuting 忽略:channelId={} 不存在", channelId);
            return;
        }
        ctx.setExecutingTaskId(taskId);
        ctx.setExecutingMacroType(macroType);
        if (macroType != null) {
            log.info("channel {} 进入执行态 taskId={} macroType={}", channelId, taskId, macroType);
        }
    }

    /** 标记通道回到空闲状态。{@code execution_end} / {@code single_resp} 到达后调用。 */
    public void markIdle(String channelId) {
        ChannelContext ctx = channels.get(channelId);
        if (ctx == null) return;
        ctx.setExecutingTaskId(null);
        ctx.setExecutingMacroType(null);
    }

    public void close(String channelId) {
        ChannelContext removed = channels.remove(channelId);
        if (removed != null) {
            log.info("channel 关通道 channelId={} vin={}", channelId, removed.getVin());
        }
    }

    /**
     * 关联通道与已获取的 ECU 锁。
     *
     * @param channelId     通道 ID
     * @param acquiredLocks {@link EcuLockRegistry#tryAcquireAll} 返回的锁列表
     */
    public void attachLocks(String channelId, List<EcuLockRegistry.AcquiredLock> acquiredLocks) {
        channelLocks.put(channelId, acquiredLocks);
        log.debug("channel 附加 ECU 锁 channelId={} 锁数={}", channelId, acquiredLocks.size());
    }

    /**
     * 取出并移除通道关联的 ECU 锁(供关通道时释放)。
     *
     * @param channelId 通道 ID
     * @return 锁列表;不存在时返回空列表
     */
    public List<EcuLockRegistry.AcquiredLock> detachLocks(String channelId) {
        List<EcuLockRegistry.AcquiredLock> locks = channelLocks.remove(channelId);
        return locks == null ? List.of() : locks;
    }

    /** 供监控/健康检查使用。 */
    public int size() { return channels.size(); }
}

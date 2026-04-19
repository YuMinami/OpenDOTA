package com.opendota.diag.channel;

import com.opendota.common.envelope.Operator;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 诊断通道运行期上下文(协议 §4 + 架构 §3.2)。
 *
 * <p>字段划分:
 * <ul>
 *   <li>不可变: {@code channelId / vin / ecuName / ecuScope / operator / openedAt}</li>
 *   <li>可变(标记当前执行的 macro): {@code executingTaskId / executingMacroType}
 *       —— 由 {@code ChannelManager.markExecuting/markIdle} 更新。
 *       v1.4 A2 的不可中断检查读本字段。</li>
 * </ul>
 *
 * <p>注意:本上下文是**进程内状态**,仅用于云端 Dispatcher 层的 A2 快速判断。
 * 跨节点一致源还是 PG 的 {@code diag_record} + {@code task_dispatch_record}(后续接入)。
 */
public class ChannelContext {

    private final String channelId;
    private final String vin;
    private final String ecuName;
    private final List<String> ecuScope;
    private final Operator operator;
    private final Instant openedAt;

    private volatile String executingTaskId;
    private volatile String executingMacroType;

    public ChannelContext(String channelId, String vin, String ecuName,
                          List<String> ecuScope, Operator operator) {
        this.channelId = Objects.requireNonNull(channelId, "channelId 必填");
        this.vin = Objects.requireNonNull(vin, "vin 必填");
        this.ecuName = Objects.requireNonNull(ecuName, "ecuName 必填");
        this.ecuScope = ecuScope == null ? List.of(ecuName) : List.copyOf(ecuScope);
        this.operator = operator;
        this.openedAt = Instant.now();
    }

    public String getChannelId() { return channelId; }
    public String getVin() { return vin; }
    public String getEcuName() { return ecuName; }
    public List<String> getEcuScope() { return ecuScope; }
    public Operator getOperator() { return operator; }
    public Instant getOpenedAt() { return openedAt; }

    public String getExecutingTaskId() { return executingTaskId; }
    public void setExecutingTaskId(String taskId) { this.executingTaskId = taskId; }

    public String getExecutingMacroType() { return executingMacroType; }
    public void setExecutingMacroType(String macroType) { this.executingMacroType = macroType; }
}

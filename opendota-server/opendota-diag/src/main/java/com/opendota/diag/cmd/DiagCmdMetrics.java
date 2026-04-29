package com.opendota.diag.cmd;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 诊断命令指标(Prometheus)。
 *
 * <p>指标:
 * <ul>
 *   <li>{@code dota_single_cmd_latency_seconds} — 从 MQTT 发布到 V2C 响应的端到端延迟</li>
 *   <li>{@code dota_single_cmd_total} — 已下发的 single_cmd 总数</li>
 *   <li>{@code dota_single_cmd_success_total} — 响应成功的 single_cmd 数</li>
 * </ul>
 *
 * <p>通过 {@link #recordDispatch(String)} / {@link #recordResponse(String, boolean)}
 * 跨 SingleCmdService → SingleRespHandler 传递 dispatch 时间戳。
 */
public class DiagCmdMetrics {

    private final Timer singleCmdLatency;
    private final Counter singleCmdTotal;
    private final Counter singleCmdSuccessTotal;
    private final Counter batchCmdTotal;
    private final Counter batchCmdSuccessTotal;
    private final Counter scriptCmdTotal;
    private final Counter scriptCmdSuccessTotal;
    private final Counter macroDispatchTotal;
    private final Counter macroSecurityTotal;
    private final Counter macroRoutineWaitTotal;
    private final Counter macroDataTransferTotal;
    private final ConcurrentHashMap<String, Long> pendingTimestamps = new ConcurrentHashMap<>();

    public DiagCmdMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "MeterRegistry 必填");
        this.singleCmdLatency = Timer.builder("dota_single_cmd_latency_seconds")
                .description("single_cmd 从下发到响应的端到端延迟")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
        this.singleCmdTotal = registry.counter("dota_single_cmd_total");
        this.singleCmdSuccessTotal = registry.counter("dota_single_cmd_success_total");
        this.batchCmdTotal = registry.counter("dota_batch_cmd_total");
        this.batchCmdSuccessTotal = registry.counter("dota_batch_cmd_success_total");
        this.scriptCmdTotal = registry.counter("dota_script_cmd_total");
        this.scriptCmdSuccessTotal = registry.counter("dota_script_cmd_success_total");
        this.macroDispatchTotal = Counter.builder("dota_macro_dispatch_total")
                .description("宏指令下发总数(按 macroType 维度)")
                .register(registry);
        this.macroSecurityTotal = Counter.builder("dota_macro_dispatch_total")
                .tag("macroType", "macro_security")
                .description("macro_security 下发次数")
                .register(registry);
        this.macroRoutineWaitTotal = Counter.builder("dota_macro_dispatch_total")
                .tag("macroType", "macro_routine_wait")
                .description("macro_routine_wait 下发次数")
                .register(registry);
        this.macroDataTransferTotal = Counter.builder("dota_macro_dispatch_total")
                .tag("macroType", "macro_data_transfer")
                .description("macro_data_transfer 下发次数")
                .register(registry);
    }

    /**
     * 记录 dispatch 时间(msgId → System.nanoTime())，同时递增 total 计数。
     */
    public void recordDispatch(String msgId) {
        singleCmdTotal.increment();
        pendingTimestamps.put(msgId, System.nanoTime());
    }

    /**
     * 计算端到端延迟并记录。成功时递增 successTotal。
     *
     * @param msgId 关联的 msgId
     * @param success 响应是否成功(status == 0)
     */
    public void recordResponse(String msgId, boolean success) {
        Long startNanos = pendingTimestamps.remove(msgId);
        if (startNanos != null) {
            long elapsed = System.nanoTime() - startNanos;
            singleCmdLatency.record(elapsed, TimeUnit.NANOSECONDS);
        }
        if (success) {
            singleCmdSuccessTotal.increment();
        }
    }

    public void recordBatchDispatch(String msgId) {
        batchCmdTotal.increment();
        pendingTimestamps.put(msgId, System.nanoTime());
    }

    public void recordBatchResponse(String msgId, boolean success) {
        Long startNanos = pendingTimestamps.remove(msgId);
        if (startNanos != null) {
            long elapsed = System.nanoTime() - startNanos;
            singleCmdLatency.record(elapsed, TimeUnit.NANOSECONDS);
        }
        if (success) {
            batchCmdSuccessTotal.increment();
        }
    }

    public void recordScriptDispatch(String msgId) {
        scriptCmdTotal.increment();
        pendingTimestamps.put(msgId, System.nanoTime());
    }

    public void recordScriptResponse(String msgId, boolean success) {
        Long startNanos = pendingTimestamps.remove(msgId);
        if (startNanos != null) {
            long elapsed = System.nanoTime() - startNanos;
            singleCmdLatency.record(elapsed, TimeUnit.NANOSECONDS);
        }
        if (success) {
            scriptCmdSuccessTotal.increment();
        }
    }

    /**
     * 记录宏指令下发(协议 §7)。
     *
     * <p>按 macroType 分维度计数,用于审计和 SLO 监控。
     *
     * @param macroTypes 本次下发包含的宏类型列表
     */
    public void recordMacroDispatch(List<String> macroTypes) {
        if (macroTypes == null) {
            return;
        }
        for (String macroType : macroTypes) {
            macroDispatchTotal.increment();
            switch (macroType) {
                case "macro_security" -> macroSecurityTotal.increment();
                case "macro_routine_wait" -> macroRoutineWaitTotal.increment();
                case "macro_data_transfer" -> macroDataTransferTotal.increment();
                default -> { /* 未知宏类型,仅计总数 */ }
            }
        }
    }

    /** 仅用于测试: 返回 pending 队列大小 */
    int pendingCount() {
        return pendingTimestamps.size();
    }
}

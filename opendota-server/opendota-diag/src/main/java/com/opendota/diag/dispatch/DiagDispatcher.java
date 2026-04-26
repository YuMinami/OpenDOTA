package com.opendota.diag.dispatch;

import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.Operator;
import com.opendota.diag.channel.ChannelContext;
import com.opendota.diag.channel.ChannelManager;
import com.opendota.diag.web.ApiError;
import com.opendota.diag.web.BusinessException;
import com.opendota.mqtt.publisher.MqttPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 诊断下发分发器(架构 §3.1 + §3.2)。
 *
 * <p>职责:
 * <ol>
 *   <li>统一构建 {@link DiagMessage} envelope(填 msgId、timestamp、operator)</li>
 *   <li>走 {@link MqttPublisher} 把 envelope 投递到 MQTT broker</li>
 *   <li>在取消 / 抢占链路集中执行 v1.4 A2 不可中断宏保护</li>
 * </ol>
 *
 * <p>**强制异步**(架构 §3.1):本类任何一个 dispatch 方法都必须在 ~ms 级返回 {@code msgId},
 * 不允许在这里 block 等车端响应。车端响应走 MQTT → Redis Pub/Sub → SSE 投递路径。
 */
@Service
public class DiagDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DiagDispatcher.class);

    private final MqttPublisher publisher;
    private final ChannelManager channelManager;

    public DiagDispatcher(MqttPublisher publisher, ChannelManager channelManager) {
        this.publisher = publisher;
        this.channelManager = channelManager;
    }

    // ============================================================
    // 单步诊断(协议 §5)
    // ============================================================

    /**
     * 下发单步 UDS 指令。
     *
     * @param channelId  已通过 {@code channel_open} 建立的通道 ID
     * @param type       步骤类型,默认 {@code raw_uds};宏类型会被 {@link ChannelManager#markExecuting} 记录
     * @param reqData    十六进制 UDS PDU,如 {@code "22F190"}
     * @param timeoutMs  车端等待 ECU 响应的超时(毫秒),默认 5000
     * @param operator   操作者(RBAC 上游已校验通过)
     * @return 生成的 {@code msgId} —— 前端用它订阅 SSE {@code diag-result}
     */
    public String dispatchSingleCmd(String channelId, String type, String reqData, Integer timeoutMs, Operator operator) {
        ChannelContext ctx = channelManager.get(channelId)
                .orElseThrow(() -> new BusinessException(ApiError.E40401,
                        "channel 不存在或已关闭: " + channelId));

        String resolvedType = type == null || type.isBlank() ? "raw_uds" : type;
        String cmdId = "cmd-" + UUID.randomUUID().toString().substring(0, 13);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cmdId", cmdId);
        payload.put("channelId", channelId);
        payload.put("type", resolvedType);
        payload.put("reqData", reqData);
        payload.put("timeoutMs", timeoutMs == null ? 5000 : timeoutMs);

        DiagMessage<Map<String, Object>> env =
                DiagMessage.c2v(ctx.getVin(), DiagAction.SINGLE_CMD, operator, payload);

        // 若这一步是宏调用,先标记到 ChannelContext,让 A2 guard 能看到
        if (resolvedType.startsWith("macro_")) {
            channelManager.markExecuting(channelId, cmdId, resolvedType);
        }

        log.info("dispatch single_cmd channelId={} cmdId={} type={} reqData={}",
                channelId, cmdId, resolvedType, reqData);
        publisher.publish(env);
        return env.msgId();
    }

    // ============================================================
    // 任务取消(协议 §12.4 + v1.4 A2)
    // ============================================================

    /**
     * 下发任务取消。v1.4 A2:当 {@code force=true} 且目标通道正在执行 {@code macro_data_transfer}
     * 或 {@code macro_security} 半程时,**一律拒绝**(无 admin 强制开关),抛
     * {@link BusinessException}(code=40305)。
     *
     * @param vin       目标车辆 VIN
     * @param taskId    要取消的任务 ID
     * @param channelId 任务所在通道(用于查询当前执行宏;{@code null} 表示任务未绑定通道)
     * @param reason    取消原因(MANUAL_CANCEL / EXPIRED / SUPERSEDED)
     * @param force     是否强制;{@code true} 触发 A2 检查
     * @param operator  操作者
     */
    public String dispatchTaskCancel(String vin, String taskId, String channelId,
                                      String reason, boolean force, Operator operator) {
        if (force && channelId != null) {
            ChannelContext ctx = channelManager.get(channelId).orElse(null);
            if (ctx != null && NonInterruptibleMacros.isProtected(ctx.getExecutingMacroType())) {
                // v1.4 A2:一律拒绝,审计日志放到 security-audit(后续接入)
                log.warn("🚫 v1.4 A2 拒绝 force_cancel: operator={} role={} taskId={} channelId={} macroType={}",
                        operator == null ? "system" : operator.id(),
                        operator == null ? null : operator.role(),
                        taskId, channelId, ctx.getExecutingMacroType());
                throw new BusinessException(
                        ApiError.E40305,
                        "任务 " + taskId + " 正在执行不可中断宏 " + ctx.getExecutingMacroType()
                                + ";v1.4 A2 一律拒绝 force_cancel,请等待宏自然完成后重试"
                );
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("reason", reason == null || reason.isBlank() ? "MANUAL_CANCEL" : reason);

        DiagMessage<Map<String, Object>> env =
                DiagMessage.c2v(vin, DiagAction.TASK_CANCEL, operator, payload);

        log.info("dispatch task_cancel taskId={} vin={} reason={} force={}", taskId, vin, reason, force);
        publisher.publish(env);
        return env.msgId();
    }

    // 通道生命周期(协议 §4.2 / §4.3)已迁移到
    // {@link com.opendota.diag.channel.ChannelOpenService} / {@code ChannelCloseService}。
}

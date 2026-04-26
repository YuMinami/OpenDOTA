package com.opendota.diag.channel;

import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.Operator;
import com.opendota.common.payload.channel.ChannelOpenPayload;
import com.opendota.common.payload.common.DoipConfig;
import com.opendota.common.payload.common.Transport;
import com.opendota.mqtt.publisher.MqttPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 通道开启服务(协议 §4.2 + REST §4.1)。
 *
 * <p>职责:
 * <ol>
 *   <li>本地生成 {@code channelId},构造类型化 {@link ChannelOpenPayload}</li>
 *   <li>登记 {@link ChannelManager}(进程内缓存,Phase 6 改 Redis+PG)</li>
 *   <li>通过 {@link MqttPublisher} 发布 {@code channel_open} envelope</li>
 *   <li>立即返回 {@code (channelId, msgId)} 供 HTTP 线程直接回 200 —— 全异步(架构 §3.1)</li>
 * </ol>
 *
 * <p>**边界**:本服务不做参数校验、RBAC、抢占策略。校验由 {@code ChannelController} 层完成,
 * RBAC / force 抢占由后续 {@code opendota-security} 切面接入。
 */
@Service
public class ChannelOpenService {

    private static final Logger log = LoggerFactory.getLogger(ChannelOpenService.class);

    private final MqttPublisher publisher;
    private final ChannelManager channelManager;

    public ChannelOpenService(MqttPublisher publisher, ChannelManager channelManager) {
        this.publisher = publisher;
        this.channelManager = channelManager;
    }

    /**
     * 下发 {@code channel_open} 并登记通道上下文。
     *
     * <p>调用方负责确保 {@code ecuScope} 非空(REST §4.1 R2 约束:单 ECU 场景应填 {@code [ecuName]})。
     * 实际"通道已建立"由车端 V2C {@code channel_event opened} 决定;此处只是 MQTT 投递 + 本地占位。
     *
     * @param command 已校验通过的开通道请求
     * @return 含本地生成的 {@code channelId} 与 envelope {@code msgId}
     */
    public ChannelOpenResult open(ChannelOpenCommand command) {
        String channelId = "ch-" + UUID.randomUUID().toString().substring(0, 13);

        ChannelOpenPayload payload = new ChannelOpenPayload(
                channelId,
                command.ecuName(),
                command.ecuScope(),
                command.transport(),
                command.txId(),
                command.rxId(),
                command.doipConfig(),
                command.globalTimeoutMs(),
                command.force(),
                command.preemptPolicy());

        DiagMessage<ChannelOpenPayload> env =
                DiagMessage.c2v(command.vin(), DiagAction.CHANNEL_OPEN, command.operator(), payload);

        channelManager.register(channelId, command.vin(), command.ecuName(),
                payload.ecuScope(), command.operator());
        publisher.publish(env);

        log.info("dispatch channel_open channelId={} vin={} ecu={} scope={} transport={}",
                channelId, command.vin(), command.ecuName(), payload.ecuScope(),
                payload.transport().wireName());
        return new ChannelOpenResult(channelId, env.msgId());
    }

    /**
     * 开通道入参(协议 §4.2)。
     *
     * <p>{@code force} 默认 {@code false} 由调用方保证;{@code preemptPolicy} 缺省由
     * {@link ChannelOpenPayload} 的 wire 默认值兜底(车端按 {@code wait} 处理,见协议 §10.7.2)。
     */
    public record ChannelOpenCommand(
            String vin,
            String ecuName,
            List<String> ecuScope,
            Transport transport,
            String txId,
            String rxId,
            DoipConfig doipConfig,
            Integer globalTimeoutMs,
            Boolean force,
            String preemptPolicy,
            Operator operator) {}

    /** 开通道返回。 */
    public record ChannelOpenResult(String channelId, String msgId) {}
}

package com.opendota.mqtt.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;

/**
 * V2C 响应处理 SPI。业务模块(diag / task / admin)实现本接口并注册为 Spring bean,
 * {@code MqttSubscriber} 会按 {@link #supports(DiagAction)} 选路投递。
 *
 * <p>实现要点:
 * <ul>
 *   <li>{@link #supports} 应该是纯函数,不要访问外部状态</li>
 *   <li>{@link #onMessage} 已经在 {@code MqttSubscriber} 注入好 MDC 5 字段(traceId / msgId / vin /
 *       operatorId / tenantId),直接写日志即可</li>
 *   <li>Listener 之间相互独立,单个 listener 抛异常不影响其它 listener</li>
 *   <li>不要在 listener 里直接回写 MQTT(否则产生环路),响应通道是 Redis Pub/Sub → SSE</li>
 * </ul>
 */
public interface V2cListener {

    /** 该 listener 是否处理给定的 act。 */
    boolean supports(DiagAction act);

    /**
     * 处理一条 V2C envelope。
     *
     * @param topic    MQTT 主题,例如 {@code dota/v1/resp/single/<VIN>}
     * @param envelope 已解析但 payload 保留为 {@link JsonNode} 的 envelope
     */
    void onMessage(String topic, DiagMessage<JsonNode> envelope);
}

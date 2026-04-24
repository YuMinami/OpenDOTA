package com.opendota.mqtt.subscriber;

import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;

/**
 * V2C 响应处理 SPI。业务模块(diag / task / admin)实现本接口并注册为 Spring bean,
 * {@link V2CMessageDispatcher} 会按 {@link #supports(DiagAction)} 选路投递。
 *
 * <p>实现要点:
 * <ul>
 *   <li>{@link #supports} 应该是纯函数,不要访问外部状态</li>
 *   <li>{@link #handle} 进入时已完成 MDC 五字段绑定(traceId / msgId / vin / operatorId / tenantId)</li>
 *   <li>Handler 之间相互独立,单个 handler 抛异常不影响其它 handler</li>
 *   <li>不要在 handler 里直接回写 MQTT(否则产生环路),响应通道是 Redis Pub/Sub → SSE</li>
 * </ul>
 */
public interface V2CHandler {

    /** 该 handler 是否处理给定 act。 */
    boolean supports(DiagAction act);

    /**
     * 处理一条 V2C envelope。
     *
     * @param topic MQTT 主题,例如 {@code dota/v1/resp/single/<VIN>}
     * @param envelope 已通过 {@code EnvelopeReader} 解析过的 envelope
     */
    void handle(String topic, DiagMessage<?> envelope);
}

package com.opendota.diag.channel;

import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.Operator;
import com.opendota.common.payload.channel.ChannelClosePayload;
import com.opendota.diag.web.ApiError;
import com.opendota.diag.web.BusinessException;
import com.opendota.mqtt.publisher.MqttPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 通道关闭服务(协议 §4.3 + REST §4.2)。
 *
 * <p>关通道与开通道对称:发 {@code channel_close} envelope → 本地清理。**车端实际释放资源后会回
 * {@code channel_event closed}**(协议 §4.4),云端这里只完成 MQTT 投递与本地登记移除。
 *
 * <p>幂等说明:对一个不存在的 {@code channelId} 直接抛 {@code 40401};避免重复 close 误删后开
 * 的同名通道(短期 UUID 碰撞概率忽略)。
 */
@Service
public class ChannelCloseService {

    private static final Logger log = LoggerFactory.getLogger(ChannelCloseService.class);

    private final MqttPublisher publisher;
    private final ChannelManager channelManager;

    public ChannelCloseService(MqttPublisher publisher, ChannelManager channelManager) {
        this.publisher = publisher;
        this.channelManager = channelManager;
    }

    /**
     * 关闭通道并下发 {@code channel_close}。
     *
     * @param channelId    待关闭的通道 ID
     * @param resetSession {@code true} 则要求车端在停 3E 80 心跳前先发 {@code 10 01} 踢回默认会话;
     *                     {@code null} 时 wire 字段省略,车端按默认行为(不复位)处理
     * @param operator     操作者(C2V 必填)
     * @return MQTT envelope 的 {@code msgId},供 SSE 关联跟踪
     * @throws BusinessException 当 {@code channelId} 不存在时(code=40401)
     */
    public ChannelCloseResult close(String channelId, Boolean resetSession, Operator operator) {
        ChannelContext ctx = channelManager.get(channelId)
                .orElseThrow(() -> new BusinessException(ApiError.E40401,
                        "channel 不存在或已关闭: " + channelId));

        ChannelClosePayload payload = new ChannelClosePayload(channelId, resetSession);
        DiagMessage<ChannelClosePayload> env =
                DiagMessage.c2v(ctx.getVin(), DiagAction.CHANNEL_CLOSE, operator, payload);

        publisher.publish(env);
        // MQTT 已 PUBACK 才落本地清理,避免发布失败时通道丢失但车端仍在持锁
        channelManager.close(channelId);

        log.info("dispatch channel_close channelId={} vin={} resetSession={}",
                channelId, ctx.getVin(), resetSession);
        return new ChannelCloseResult(channelId, ctx.getVin(), env.msgId());
    }

    /** 关通道返回。 */
    public record ChannelCloseResult(String channelId, String vin, String msgId) {}
}

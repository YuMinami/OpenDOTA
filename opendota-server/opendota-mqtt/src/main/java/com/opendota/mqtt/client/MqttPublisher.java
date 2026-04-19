package com.opendota.mqtt.client;

import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.mqtt.codec.EnvelopeCodec;
import com.opendota.mqtt.config.MqttProperties;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * C2V 报文发布器。
 *
 * <p>根据 {@link DiagMessage#act()} 自动推导 Topic scene 段(协议 §2.2),统一走 QoS 1。
 * 调用方只需要构造 {@link DiagMessage} 再 {@link #publish(DiagMessage)}。
 *
 * <p>禁止在这里做业务校验、权限校验、审计;这些属于 {@code opendota-diag} / {@code opendota-security} 层。
 * 本类只负责"把信封塞进 MQTT 的邮筒"——知道邮筒在哪、信封长什么样,不管信里写了什么。
 */
public class MqttPublisher {

    private static final Logger log = LoggerFactory.getLogger(MqttPublisher.class);

    private final MqttClient client;
    private final EnvelopeCodec codec;
    private final MqttProperties props;

    public MqttPublisher(MqttClient client, EnvelopeCodec codec, MqttProperties props) {
        this.client = client;
        this.codec = codec;
        this.props = props;
    }

    /**
     * 发布一条 C2V envelope。Topic 按 {@code <prefix>/cmd/<scene>/<vin>} 推导。
     *
     * @throws IllegalStateException MQTT 底层故障(连接丢失、QoS 握手失败等)
     */
    public void publish(DiagMessage<?> env) {
        String topic = resolveTopic(env);
        byte[] bytes = codec.serialize(env);
        MqttMessage msg = new MqttMessage(bytes);
        msg.setQos(props.getDefaultQos());
        try {
            client.publish(topic, msg);
            log.info("📤 C2V publish topic={} act={} msgId={} vin={} bytes={}",
                    topic, env.act().wireName(), env.msgId(), env.vin(), bytes.length);
        } catch (MqttException e) {
            log.error("MQTT publish 失败 topic={} act={} msgId={}",
                    topic, env.act().wireName(), env.msgId(), e);
            throw new IllegalStateException("MQTT publish 失败: " + topic, e);
        }
    }

    /**
     * 手动指定 Topic 发布,兜底用(例如 admin 场景需要打 retained 消息)。大部分业务不应该调用这个。
     */
    public void publishToTopic(String topic, DiagMessage<?> env) {
        byte[] bytes = codec.serialize(env);
        MqttMessage msg = new MqttMessage(bytes);
        msg.setQos(props.getDefaultQos());
        try {
            client.publish(topic, msg);
            log.info("📤 C2V publish(custom topic) topic={} act={} msgId={}", topic, env.act().wireName(), env.msgId());
        } catch (MqttException e) {
            throw new IllegalStateException("MQTT publish 失败: " + topic, e);
        }
    }

    private String resolveTopic(DiagMessage<?> env) {
        return "%s/cmd/%s/%s".formatted(props.getTopicPrefix(), sceneOf(env.act()), env.vin());
    }

    /** act → topic scene 段(协议 §2.2)。未定义的 C2V act 直接抛错,防止写错 topic。 */
    private static String sceneOf(DiagAction act) {
        return switch (act) {
            case CHANNEL_OPEN, CHANNEL_CLOSE -> "channel";
            case SINGLE_CMD -> "single";
            case BATCH_CMD -> "batch";
            case SCHEDULE_SET, SCHEDULE_CANCEL -> "schedule";
            case SCRIPT_CMD -> "script";
            case QUEUE_QUERY, QUEUE_DELETE, QUEUE_PAUSE, QUEUE_RESUME -> "queue";
            case TASK_PAUSE, TASK_RESUME, TASK_CANCEL, TASK_QUERY -> "task";
            case SIGNAL_CATALOG_PUSH -> "signal";
            case TIME_SYNC_REQUEST -> "time";
            default -> throw new IllegalArgumentException(
                    "act=" + act.wireName() + " 不是合法的 C2V 动作,无 topic 映射");
        };
    }
}

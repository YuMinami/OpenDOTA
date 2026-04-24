package com.opendota.mqtt.publisher;

import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.EnvelopeWriter;
import com.opendota.mqtt.config.MqttProperties;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * C2V 报文发布器。
 *
 * <p>根据 {@link DiagMessage#act()} 自动推导 Topic scene 段(协议 §2.2),默认走 QoS 1。
 * 对 QoS 1 发布显式等待 PUBACK,确保方法返回前 broker 已确认接收。
 *
 * <p>禁止在这里做业务校验、权限校验、审计;这些属于 {@code opendota-diag} / {@code opendota-security} 层。
 * 本类只负责"把信封塞进 MQTT 的邮筒"——知道邮筒在哪、信封长什么样,不管信里写了什么。
 */
public class MqttPublisher {

    private static final Logger log = LoggerFactory.getLogger(MqttPublisher.class);

    private final MqttClient client;
    private final EnvelopeWriter envelopeWriter;
    private final MqttProperties props;
    private final MqttPublishMetrics metrics;

    public MqttPublisher(MqttClient client, EnvelopeWriter envelopeWriter,
                         MqttProperties props, MqttPublishMetrics metrics) {
        this.client = client;
        this.envelopeWriter = envelopeWriter;
        this.props = props;
        this.metrics = metrics;
    }

    /**
     * 发布一条 C2V envelope。Topic 按 {@code <prefix>/cmd/<scene>/<vin>} 推导。
     *
     * @throws MqttPublishException MQTT 底层故障(连接丢失、QoS 握手失败等)
     */
    public void publish(DiagMessage<?> env) {
        publish(resolveTopic(env), env, props.getDefaultQos());
    }

    /**
     * 手动指定 Topic + QoS 发布。QoS 1 会显式等待 PUBACK。
     */
    public void publish(String topic, DiagMessage<?> env, int qos) {
        byte[] bytes = envelopeWriter.write(env);
        MqttMessage msg = new MqttMessage(bytes);
        msg.setQos(qos);
        long startNanos = System.nanoTime();
        try {
            if (!client.isConnected()) {
                throw new MqttPublishException("MQTT client 未连接: " + topic);
            }
            IMqttDeliveryToken token = client.getTopic(topic).publish(msg);
            token.waitForCompletion();
            metrics.recordSuccess(System.nanoTime() - startNanos);
            log.info("C2V publish topic={} act={} msgId={} vin={} qos={} bytes={}",
                    topic, env.act().wireName(), env.msgId(), env.vin(), qos, bytes.length);
        } catch (MqttException e) {
            metrics.recordFailure(System.nanoTime() - startNanos);
            log.error("MQTT publish 失败 topic={} act={} msgId={}",
                    topic, env.act().wireName(), env.msgId(), e);
            throw new MqttPublishException("MQTT publish 失败: " + topic, e);
        } catch (RuntimeException e) {
            metrics.recordFailure(System.nanoTime() - startNanos);
            throw e;
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
            case SIGNAL_CATALOG_PUSH -> "signal-catalog";
            case TIME_SYNC_REQUEST -> "time";
            default -> throw new IllegalArgumentException(
                    "act=" + act.wireName() + " 不是合法的 C2V 动作,无 topic 映射");
        };
    }
}

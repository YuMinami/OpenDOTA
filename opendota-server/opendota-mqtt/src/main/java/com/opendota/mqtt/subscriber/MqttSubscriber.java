package com.opendota.mqtt.subscriber;

import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.EnvelopeReader;
import com.opendota.mqtt.config.MqttProperties;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * V2C 订阅器。订阅 {@code dota/v1/+/+/+} 与遗留的 {@code ack/+} topic,收到消息后走
 * {@link EnvelopeReader} → {@link MdcBinder} → {@link V2CMessageDispatcher} 链路。
 */
public class MqttSubscriber implements MqttCallback {

    private static final Logger log = LoggerFactory.getLogger(MqttSubscriber.class);

    private final MqttClient client;
    private final EnvelopeReader envelopeReader;
    private final MqttProperties props;
    private final V2CMessageDispatcher dispatcher;
    private final MdcBinder mdcBinder;

    public MqttSubscriber(MqttClient client, EnvelopeReader envelopeReader, MqttProperties props,
                          V2CMessageDispatcher dispatcher, MdcBinder mdcBinder) {
        this.client = client;
        this.envelopeReader = envelopeReader;
        this.props = props;
        this.dispatcher = dispatcher;
        this.mdcBinder = mdcBinder;
    }

    /** 由 Lifecycle bean 在 {@code @PostConstruct} 阶段调用。 */
    public void start() throws MqttException {
        client.setCallback(this);
        int qos = props.getDefaultQos();
        List<String> topics = subscriptionTopics();
        for (String topic : topics) {
            client.subscribe(topic, qos);
        }
        log.info("✅ cloud MQTT subscriber 订阅就绪: topics={} handlers={}", topics, dispatcher.handlerCount());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        DiagMessage<?> envelope;
        try {
            envelope = envelopeReader.parse(message.getPayload());
        } catch (Exception e) {
            log.warn("无法解析 V2C envelope topic={} bytes={}", topic, message.getPayload().length, e);
            return;
        }

        try (MdcBinder.Scope ignored = mdcBinder.bind(envelope)) {
            log.info("📥 V2C arrive topic={} act={} bytes={} payloadType={}",
                    topic, envelope.act().wireName(), message.getPayload().length, payloadTypeOf(envelope));
            dispatcher.dispatch(topic, envelope);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("cloud MQTT 连接丢失,Paho 将自动重连", cause);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // QoS 1 ack;不需要额外处理
    }

    private List<String> subscriptionTopics() {
        String prefix = props.getTopicPrefix();
        return List.of(
                prefix + "/+/+/+",
                prefix + "/ack/+"
        );
    }

    private static String payloadTypeOf(DiagMessage<?> envelope) {
        Object payload = envelope.payload();
        return payload == null ? "null" : payload.getClass().getSimpleName();
    }
}

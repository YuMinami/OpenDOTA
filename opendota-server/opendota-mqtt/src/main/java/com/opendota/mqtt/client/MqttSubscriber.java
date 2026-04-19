package com.opendota.mqtt.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.Operator;
import com.opendota.mqtt.codec.EnvelopeCodec;
import com.opendota.mqtt.config.MqttProperties;
import com.opendota.mqtt.listener.V2cListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;

/**
 * V2C 订阅器。订阅 {@code resp/*} / {@code event/*} / {@code ack/*},解析 envelope 后按
 * {@code act} 投递给 {@link V2cListener}。
 *
 * <h3>MDC 5 字段(架构 §9.1)</h3>
 * <pre>
 *   traceId     — 从 envelope.traceparent 提取 W3C Trace Context 的 trace-id 段,缺省 "-"
 *   msgId       — envelope.msgId(UUID),贯穿 MQTT → ODX → SSE
 *   vin         — 车架号
 *   operatorId  — envelope.operator.id(V2C 可能为 null,系统事件标记 "system")
 *   tenantId    — envelope.operator.tenantId(V2C 主动事件可能为 null,标记 "-")
 * </pre>
 * 字段在 {@link #messageArrived} 入口注入,finally 里清理,避免虚拟线程复用造成 MDC 串标。
 *
 * <h3>Listener 容错</h3>
 * Listener 之间相互独立,任一抛异常不影响后续 listener;异常会被记录并丢弃,不会让 Paho 重试
 * (QoS 1 已经 ack 过,重投也没意义)。
 */
public class MqttSubscriber implements MqttCallback {

    private static final Logger log = LoggerFactory.getLogger(MqttSubscriber.class);

    private final MqttClient client;
    private final EnvelopeCodec codec;
    private final MqttProperties props;
    private final List<V2cListener> listeners;

    public MqttSubscriber(MqttClient client, EnvelopeCodec codec, MqttProperties props, List<V2cListener> listeners) {
        this.client = client;
        this.codec = codec;
        this.props = props;
        this.listeners = List.copyOf(listeners);
    }

    /** 由 Lifecycle bean 在 {@code @PostConstruct} 阶段调用。 */
    public void start() throws MqttException {
        client.setCallback(this);
        String prefix = props.getTopicPrefix();
        int qos = props.getDefaultQos();
        client.subscribe(prefix + "/resp/+/+", qos);
        client.subscribe(prefix + "/event/+/+", qos);
        client.subscribe(prefix + "/ack/+", qos);
        log.info("✅ cloud MQTT subscriber 订阅就绪: prefix={} listeners={}", prefix, listeners.size());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        DiagMessage<JsonNode> env;
        try {
            env = codec.deserialize(message.getPayload());
        } catch (Exception e) {
            log.warn("无法解析 V2C envelope topic={} bytes={}", topic, message.getPayload().length, e);
            return;
        }
        try {
            injectMdc(env);
            log.info("📥 V2C arrive topic={} act={} bytes={}",
                    topic, env.act().wireName(), message.getPayload().length);
            dispatch(topic, env);
        } finally {
            clearMdc();
        }
    }

    private void dispatch(String topic, DiagMessage<JsonNode> env) {
        boolean any = false;
        for (V2cListener listener : listeners) {
            if (!listener.supports(env.act())) continue;
            any = true;
            try {
                listener.onMessage(topic, env);
            } catch (Exception e) {
                log.error("V2cListener {} 处理异常 topic={} act={}",
                        listener.getClass().getSimpleName(), topic, env.act().wireName(), e);
            }
        }
        if (!any) {
            log.debug("V2C 无 listener 关心 topic={} act={}", topic, env.act().wireName());
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

    // ================= MDC 5 字段注入 =================

    static final String MDC_TRACE_ID = "traceId";
    static final String MDC_MSG_ID = "msgId";
    static final String MDC_VIN = "vin";
    static final String MDC_OPERATOR_ID = "operatorId";
    static final String MDC_TENANT_ID = "tenantId";

    private static void injectMdc(DiagMessage<JsonNode> env) {
        MDC.put(MDC_TRACE_ID, traceIdOf(env.traceparent()));
        MDC.put(MDC_MSG_ID, env.msgId());
        MDC.put(MDC_VIN, env.vin());
        Operator op = env.operator();
        MDC.put(MDC_OPERATOR_ID, op == null ? "system" : String.valueOf(op.id()));
        MDC.put(MDC_TENANT_ID, op == null || op.tenantId() == null ? "-" : op.tenantId());
    }

    private static void clearMdc() {
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_MSG_ID);
        MDC.remove(MDC_VIN);
        MDC.remove(MDC_OPERATOR_ID);
        MDC.remove(MDC_TENANT_ID);
    }

    /** 从 W3C traceparent (version-traceId-spanId-flags) 提取 32 位 traceId。 */
    private static String traceIdOf(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) return "-";
        String[] parts = traceparent.split("-");
        return parts.length >= 2 && !parts[1].isBlank() ? parts[1] : "-";
    }
}

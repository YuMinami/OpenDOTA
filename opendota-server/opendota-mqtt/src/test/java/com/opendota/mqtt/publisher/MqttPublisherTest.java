package com.opendota.mqtt.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.EnvelopeWriter;
import com.opendota.common.envelope.Operator;
import com.opendota.common.envelope.OperatorRole;
import com.opendota.common.payload.single.SingleCmdPayload;
import com.opendota.mqtt.config.MqttProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttTopic;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MqttPublisherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MqttPublishMetrics metrics = new MqttPublishMetrics(registry);
    private final MqttProperties props = new MqttProperties();

    @Test
    void publishUsesTopicAndQosAndWaitsForPubAck() throws Exception {
        MqttClient client = mock(MqttClient.class);
        MqttTopic topic = mock(MqttTopic.class);
        MqttDeliveryToken token = mock(MqttDeliveryToken.class);
        when(client.isConnected()).thenReturn(true);
        when(client.getTopic("dota/v1/cmd/single/LSVWA234567890123")).thenReturn(topic);
        when(topic.publish(any(MqttMessage.class))).thenReturn(token);

        MqttPublisher publisher = new MqttPublisher(client, new EnvelopeWriter(mapper), props, metrics);
        DiagMessage<SingleCmdPayload> env = new DiagMessage<>(
                "msg-001",
                1713301200000L,
                "LSVWA234567890123",
                DiagAction.SINGLE_CMD,
                new Operator("eng-1", OperatorRole.ENGINEER, "tenant-a", "DIAG-1"),
                null,
                new SingleCmdPayload("cmd-1", "ch-1", "raw_uds", "22F190", 5000)
        );

        publisher.publish(env);

        ArgumentCaptor<MqttMessage> messageCaptor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(topic).publish(messageCaptor.capture());
        verify(token).waitForCompletion();
        assertEquals(1, messageCaptor.getValue().getQos());
        assertTrue(new String(messageCaptor.getValue().getPayload(), StandardCharsets.UTF_8)
                .contains("\"msgId\":\"msg-001\""));
        assertEquals(1.0d, registry.counter("dota_mqtt_publish_total").count());
        assertEquals(0.0d, registry.counter("dota_mqtt_publish_failed_total").count());
    }

    @Test
    void publishFailureRaisesDomainExceptionAndIncrementsFailureMetric() throws Exception {
        MqttClient client = mock(MqttClient.class);
        MqttTopic topic = mock(MqttTopic.class);
        when(client.isConnected()).thenReturn(true);
        when(client.getTopic(eq("dota/v1/cmd/single/LSVWA234567890123"))).thenReturn(topic);
        when(topic.publish(any(MqttMessage.class))).thenThrow(new MqttException(32103));

        MqttPublisher publisher = new MqttPublisher(client, new EnvelopeWriter(mapper), props, metrics);

        MqttPublishException ex = assertThrows(MqttPublishException.class, () ->
                publisher.publish("dota/v1/cmd/single/LSVWA234567890123", new DiagMessage<>(
                        "msg-001",
                        1713301200000L,
                        "LSVWA234567890123",
                        DiagAction.SINGLE_CMD,
                        new Operator("eng-1", OperatorRole.ENGINEER, "tenant-a", "DIAG-1"),
                        null,
                        new SingleCmdPayload("cmd-1", "ch-1", "raw_uds", "22F190", 5000)
                ), 1));

        assertEquals("MQTT publish 失败: dota/v1/cmd/single/LSVWA234567890123", ex.getMessage());
        assertEquals(0.0d, registry.counter("dota_mqtt_publish_total").count());
        assertEquals(1.0d, registry.counter("dota_mqtt_publish_failed_total").count());
    }
}

package com.opendota.mqtt.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.Operator;
import com.opendota.common.envelope.OperatorRole;
import com.opendota.common.payload.single.SingleCmdPayload;
import com.opendota.mqtt.client.CloudMqttClientFactory;
import com.opendota.mqtt.config.MqttProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MqttPublisherLiveBrokerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishCanBeConsumedByRealSubscriber() throws Exception {
        assumeTrue(isBrokerReachable("127.0.0.1", 1883), "本地 MQTT broker 未启动，跳过集成测试");

        String vin = "LSVWA234567890123";
        String topic = "dota/v1/cmd/single/" + vin;
        String subscriberId = "opendota-it-sub-" + UUID.randomUUID().toString().substring(0, 8);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> payloadRef = new AtomicReference<>();

        MqttProperties props = new MqttProperties();
        props.setBrokerUrl("tcp://127.0.0.1:1883");

        MqttClient subscriber = new MqttClient(props.getBrokerUrl(), subscriberId);
        try {
            subscriber.connect(subscriberOptions(props));
            subscriber.subscribe(topic, 1, (receivedTopic, message) -> {
                payloadRef.set(new String(message.getPayload(), StandardCharsets.UTF_8));
                latch.countDown();
            });

            MqttClient publisherClient = CloudMqttClientFactory.build(props);
            try {
                MqttPublisher publisher = new MqttPublisher(
                        publisherClient,
                        new com.opendota.common.envelope.EnvelopeWriter(mapper),
                        props,
                        new MqttPublishMetrics(new SimpleMeterRegistry())
                );
                DiagMessage<SingleCmdPayload> env = new DiagMessage<>(
                        "msg-it-001",
                        1713301200000L,
                        vin,
                        DiagAction.SINGLE_CMD,
                        new Operator("eng-1", OperatorRole.ENGINEER, "tenant-a", "DIAG-IT-1"),
                        null,
                        new SingleCmdPayload("cmd-1", "ch-1", "raw_uds", "22F190", 5000)
                );

                publisher.publish(env);

                assertTrue(latch.await(5, TimeUnit.SECONDS), "订阅端未在 5s 内收到消息");
                assertNotNull(payloadRef.get());
                DiagMessage<?> parsed = mapper.readValue(payloadRef.get(), DiagMessage.class);
                assertEquals("msg-it-001", parsed.msgId());
            } finally {
                if (publisherClient.isConnected()) {
                    publisherClient.disconnect();
                }
                publisherClient.close();
            }
        } finally {
            if (subscriber.isConnected()) {
                subscriber.disconnect();
            }
            subscriber.close();
        }
    }

    private static MqttConnectOptions subscriberOptions(MqttProperties props) {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setConnectionTimeout((int) Duration.ofSeconds(5).toSeconds());
        options.setKeepAliveInterval(props.getKeepAliveSeconds());
        options.setUserName(props.getUsername());
        options.setPassword(props.getPassword().toCharArray());
        return options;
    }

    private static boolean isBrokerReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

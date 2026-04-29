package com.opendota.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opendota.OpenDotaApplication;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 B-5 符合性测试(协议附录 D)。
 *
 * <p>验证多 ECU 锁获取场景:
 * <ul>
 *   <li>B-5: ecuScope 含多个 ECU(乱序) → 模拟器按字典序获取锁 → opened</li>
 * </ul>
 *
 * <p>C-1 / C-2 为云端锁机制测试,见 {@code EcuLockConformanceTest}(单元级)。
 */
@Tag("conformance")
@EnabledIfSystemProperty(named = "opendota.conformance.enabled", matches = "true")
@ActiveProfiles("dev")
@SpringBootTest(
        classes = OpenDotaApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.locations=classpath:db/migration,classpath:db/dev",
                "opendota.mqtt.client-id-prefix=opendota-conformance-multi-ecu"
        })
class Phase3MultiEcuConformanceTest {

    private static final String VIN = "LSVWA234567890123";
    private static final String CHANNEL_EVENT_TOPIC = "dota/v1/event/channel/" + VIN;
    private static final String CMD_CHANNEL_TOPIC = "dota/v1/cmd/channel/" + VIN;

    private final ObjectMapper mapper = new ObjectMapper();
    private final CopyOnWriteArrayList<MqttClient> mqttClients = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        for (MqttClient client : mqttClients) {
            try {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            } catch (Exception ignored) {
            }
        }
        mqttClients.clear();
    }

    /**
     * B-5: 多 ECU 锁获取成功。
     *
     * <p>发送 channel_open 带 ecuScope ["BMS", "GW", "VCU"](故意乱序),
     * 验证模拟器接受并返回 opened,且 ecuScope 包含全部 3 个 ECU。
     */
    @Test
    void multiEcuLockAcquisitionB5() throws Exception {
        // 订阅 channel_event 响应
        ResponseProbe probe = subscribeChannelEvent();

        // 构造 channel_open:ecuScope 乱序 ["BMS", "GW", "VCU"]
        String channelId = "ch-b5-" + UUID.randomUUID().toString().substring(0, 8);
        ObjectNode envelope = baseChannelOpenEnvelope(channelId);
        ArrayNode ecuScope = mapper.createArrayNode();
        ecuScope.add("BMS");
        ecuScope.add("GW");
        ecuScope.add("VCU");
        ((ObjectNode) envelope.get("payload")).set("ecuScope", ecuScope);

        publishEnvelope(CMD_CHANNEL_TOPIC, envelope);

        // 验证模拟器返回 opened
        assertTrue(probe.awaitMessage(5), "B-5: 应收到 channel_event");
        JsonNode event = probe.firstMessage();
        assertEquals("opened", event.path("payload").path("event").asText(),
                "B-5: 期望 opened");

        // 验证返回的 ecuScope 包含全部 ECU
        JsonNode responseScope = event.path("payload").path("ecuScope");
        assertTrue(responseScope.isArray(), "B-5: ecuScope 应为数组");
        assertTrue(responseScope.size() >= 3,
                "B-5: ecuScope 应包含至少 3 个 ECU,实际=" + responseScope.size());

        // 清理:关闭通道释放锁
        closeChannel(channelId);
    }

    // ========== 辅助方法 ==========

    private ResponseProbe subscribeChannelEvent() throws Exception {
        MqttClient subscriber = mqttClient("opendota-conf-b5-event");
        ResponseProbe probe = new ResponseProbe();
        subscriber.connect(connectOptions());
        subscriber.subscribe(CHANNEL_EVENT_TOPIC, 1, (t, message) -> {
            JsonNode envelope = mapper.readTree(
                    new String(message.getPayload(), StandardCharsets.UTF_8));
            probe.capture(envelope);
        });
        return probe;
    }

    private void publishEnvelope(String topic, ObjectNode envelope) throws Exception {
        MqttClient publisher = mqttClient("opendota-conf-b5-pub");
        publisher.connect(connectOptions());
        publisher.publish(topic, mapper.writeValueAsBytes(envelope), 1, false);
        publisher.disconnect();
    }

    private void closeChannel(String channelId) throws Exception {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("msgId", UUID.randomUUID().toString());
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("vin", VIN);
        envelope.put("act", "channel_close");
        envelope.set("operator", mapper.valueToTree(Map.of(
                "id", "operator-conformance",
                "role", "engineer",
                "tenantId", "default-tenant",
                "ticketId", "TICKET-CONFORMANCE"
        )));
        ObjectNode payload = mapper.createObjectNode();
        payload.put("channelId", channelId);
        envelope.set("payload", payload);
        publishEnvelope(CMD_CHANNEL_TOPIC, envelope);
    }

    private ObjectNode baseChannelOpenEnvelope(String channelId) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("msgId", UUID.randomUUID().toString());
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("vin", VIN);
        envelope.put("act", "channel_open");
        envelope.set("operator", mapper.valueToTree(Map.of(
                "id", "operator-conformance",
                "role", "engineer",
                "tenantId", "default-tenant",
                "ticketId", "TICKET-CONFORMANCE"
        )));
        ObjectNode payload = mapper.createObjectNode();
        payload.put("channelId", channelId);
        payload.put("ecuName", "VCU");
        payload.put("transport", "UDS_ON_CAN");
        payload.put("txId", "0x7E0");
        payload.put("rxId", "0x7E8");
        envelope.set("payload", payload);
        return envelope;
    }

    private MqttClient mqttClient(String prefix) throws Exception {
        MqttClient client = new MqttClient(
                "tcp://127.0.0.1:1883",
                prefix + "-" + UUID.randomUUID().toString().substring(0, 8),
                new MemoryPersistence());
        mqttClients.add(client);
        return client;
    }

    private static MqttConnectOptions connectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(false);
        options.setCleanSession(true);
        options.setConnectionTimeout((int) Duration.ofSeconds(5).toSeconds());
        options.setKeepAliveInterval((int) Duration.ofSeconds(30).toSeconds());
        options.setUserName("opendota-cloud");
        options.setPassword("opendota-cloud".toCharArray());
        return options;
    }

    private static final class ResponseProbe {
        private final CountDownLatch firstMessage = new CountDownLatch(1);
        private final CopyOnWriteArrayList<JsonNode> envelopes = new CopyOnWriteArrayList<>();

        private void capture(JsonNode envelope) {
            envelopes.add(envelope);
            firstMessage.countDown();
        }

        private boolean awaitMessage(long seconds) throws InterruptedException {
            return firstMessage.await(seconds, TimeUnit.SECONDS);
        }

        private JsonNode firstMessage() {
            return envelopes.getFirst();
        }
    }
}

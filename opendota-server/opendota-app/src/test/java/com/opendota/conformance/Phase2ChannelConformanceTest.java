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
 * Phase 2 B 组符合性测试(协议附录 D)。
 *
 * <p>验证 Java 模拟器对 channel_open ECU 锁的守卫行为:
 * <ul>
 *   <li>B-1: ecuScope 缺失 → rejected ECU_SCOPE_REQUIRED</li>
 *   <li>B-2: 单 ECU 锁成功 → opened</li>
 *   <li>B-3: 同 ECU 二次占用 → rejected ECU_ALREADY_OCCUPIED</li>
 *   <li>B-4: ecuScope 外 ECU 操作 → single_resp ECU_NOT_IN_SCOPE</li>
 * </ul>
 *
 * <p>四个用例在同一方法内按序执行:B-3/B-4 依赖 B-2 建立的通道状态;
 * 合并为单一方法避免并发 MQTT 消息交叉干扰。
 */
@Tag("conformance")
@EnabledIfSystemProperty(named = "opendota.conformance.enabled", matches = "true")
@ActiveProfiles("dev")
@SpringBootTest(
        classes = OpenDotaApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.locations=classpath:db/migration,classpath:db/dev",
                "opendota.mqtt.client-id-prefix=opendota-conformance-channel"
        })
class Phase2ChannelConformanceTest {

    private static final String VIN = "LSVWA234567890123";
    private static final String CHANNEL_EVENT_TOPIC = "dota/v1/event/channel/" + VIN;
    private static final String SINGLE_RESP_TOPIC = "dota/v1/resp/single/" + VIN;
    private static final String CMD_CHANNEL_TOPIC = "dota/v1/cmd/channel/" + VIN;
    private static final String CMD_SINGLE_TOPIC = "dota/v1/cmd/single/" + VIN;

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
     * B-1 ~ B-4 顺序执行:ecuScope 缺失 → 单 ECU 锁 → 二次占用 → scope 外操作。
     */
    @Test
    void channelConformanceB1toB4() throws Exception {
        // ===== B-1: ecuScope 缺失 → rejected ECU_SCOPE_REQUIRED =====
        String b1ChannelId = "ch-b1-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseProbe b1Probe = subscribeChannelEvent();

        ObjectNode b1Envelope = baseChannelOpenEnvelope(b1ChannelId);
        // 不设置 ecuScope
        publishEnvelope(CMD_CHANNEL_TOPIC, b1Envelope);

        assertTrue(b1Probe.awaitMessage(3), "B-1: 应收到 channel_event");
        JsonNode b1Event = b1Probe.firstMessage();
        assertEquals("rejected", b1Event.path("payload").path("event").asText(),
                "B-1: 期望 rejected");
        assertEquals("ECU_SCOPE_REQUIRED", b1Event.path("payload").path("reason").asText(),
                "B-1: 期望 ECU_SCOPE_REQUIRED");

        // ===== B-2: 单 ECU 锁成功 → opened =====
        String b2ChannelId = "ch-b2-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseProbe b2Probe = subscribeChannelEvent();

        ObjectNode b2Envelope = baseChannelOpenEnvelope(b2ChannelId);
        ArrayNode ecuScope = mapper.createArrayNode();
        ecuScope.add("VCU");
        ((ObjectNode) b2Envelope.get("payload")).set("ecuScope", ecuScope);
        publishEnvelope(CMD_CHANNEL_TOPIC, b2Envelope);

        assertTrue(b2Probe.awaitMessage(3), "B-2: 应收到 channel_event");
        JsonNode b2Event = b2Probe.firstMessage();
        assertEquals("opened", b2Event.path("payload").path("event").asText(),
                "B-2: 期望 opened");
        assertEquals("0x01", b2Event.path("payload").path("currentSession").asText(),
                "B-2: 期望 currentSession=0x01");

        // ===== B-3: 同 ECU 二次占用 → rejected ECU_ALREADY_OCCUPIED =====
        String b3ChannelId = "ch-b3-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseProbe b3Probe = subscribeChannelEvent();

        ObjectNode b3Envelope = baseChannelOpenEnvelope(b3ChannelId);
        ArrayNode b3Scope = mapper.createArrayNode();
        b3Scope.add("VCU");
        ((ObjectNode) b3Envelope.get("payload")).set("ecuScope", b3Scope);
        publishEnvelope(CMD_CHANNEL_TOPIC, b3Envelope);

        assertTrue(b3Probe.awaitMessage(3), "B-3: 应收到 channel_event");
        JsonNode b3Event = b3Probe.firstMessage();
        assertEquals("rejected", b3Event.path("payload").path("event").asText(),
                "B-3: 期望 rejected");
        assertEquals("ECU_ALREADY_OCCUPIED", b3Event.path("payload").path("reason").asText(),
                "B-3: 期望 ECU_ALREADY_OCCUPIED");

        // ===== B-4: ecuScope 外 ECU 操作 → single_resp ECU_NOT_IN_SCOPE =====
        ResponseProbe respProbe = subscribeSingleResp();
        ObjectNode cmdEnv = baseSingleCmdEnvelope(b2ChannelId, "BMS");
        publishEnvelope(CMD_SINGLE_TOPIC, cmdEnv);

        assertTrue(respProbe.awaitMessage(3), "B-4: 应收到 single_resp");
        JsonNode b4Resp = respProbe.firstMessage();
        assertEquals(1, b4Resp.path("payload").path("status").asInt(),
                "B-4: 期望 status=1(error)");
        assertEquals("ECU_NOT_IN_SCOPE", b4Resp.path("payload").path("errorCode").asText(),
                "B-4: 期望 ECU_NOT_IN_SCOPE");
    }

    // ========== 辅助方法 ==========

    private ResponseProbe subscribeChannelEvent() throws Exception {
        return subscribe(CHANNEL_EVENT_TOPIC, "opendota-conf-ch-event");
    }

    private ResponseProbe subscribeSingleResp() throws Exception {
        return subscribe(SINGLE_RESP_TOPIC, "opendota-conf-single-resp");
    }

    private ResponseProbe subscribe(String topic, String clientPrefix) throws Exception {
        MqttClient subscriber = mqttClient(clientPrefix);
        ResponseProbe probe = new ResponseProbe();
        subscriber.connect(connectOptions());
        subscriber.subscribe(topic, 1, (t, message) -> {
            JsonNode envelope = mapper.readTree(new String(message.getPayload(), StandardCharsets.UTF_8));
            probe.capture(envelope);
        });
        return probe;
    }

    private void publishEnvelope(String topic, ObjectNode envelope) throws Exception {
        MqttClient publisher = mqttClient("opendota-conf-pub");
        publisher.connect(connectOptions());
        publisher.publish(topic, mapper.writeValueAsBytes(envelope), 1, false);
        publisher.disconnect();
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

    private ObjectNode baseSingleCmdEnvelope(String channelId, String ecuName) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("msgId", UUID.randomUUID().toString());
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("vin", VIN);
        envelope.put("act", "single_cmd");
        envelope.set("operator", mapper.valueToTree(Map.of(
                "id", "operator-conformance",
                "role", "engineer",
                "tenantId", "default-tenant",
                "ticketId", "TICKET-CONFORMANCE"
        )));
        ObjectNode payload = mapper.createObjectNode();
        payload.put("cmdId", "cmd-" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("channelId", channelId);
        payload.put("ecuName", ecuName);
        payload.put("type", "raw_uds");
        payload.put("reqData", "22F190");
        payload.put("timeoutMs", 5000);
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

        private JsonNode lastMessage() {
            return envelopes.getLast();
        }
    }
}

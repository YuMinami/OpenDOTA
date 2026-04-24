package com.opendota.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opendota.OpenDotaApplication;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 A 组符合性测试。
 *
 * <p>这些用例直接向 MQTT Broker 投递原始 C2V envelope,验证 Java 模拟器对协议附录 D A-1~A-4
 * 的最小守卫行为。默认 `mvn test` 会排除该 Tag,只有 `-Pconformance` 才执行。
 */
@Tag("conformance")
@EnabledIfSystemProperty(named = "opendota.conformance.enabled", matches = "true")
@ActiveProfiles("dev")
@SpringBootTest(
        classes = OpenDotaApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.locations=classpath:db/migration,classpath:db/dev",
                "opendota.mqtt.client-id-prefix=opendota-conformance-cloud"
        })
class Phase1EnvelopeConformanceTest {

    private static final String VIN = "LSVWA234567890123";
    private static final String RESP_TOPIC = "dota/v1/resp/single/" + VIN;
    private static final String CMD_TOPIC = "dota/v1/cmd/single/" + VIN;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
                // 测试回收路径忽略关闭异常
            }
        }
        mqttClients.clear();
    }

    @Test
    void a1_missingOperatorShouldBeRejectedAndAudited() throws Exception {
        String msgId = "a1-" + UUID.randomUUID();
        ResponseProbe probe = subscribeSingleResp();

        publishEnvelope(validSingleCmd(msgId, System.currentTimeMillis(), null));

        assertFalse(probe.awaitMessage(2), "缺失 operator 不应收到 single_resp");
        assertAuditEventually(msgId, "SECURITY_VIOLATION");
    }

    @Test
    void a2_staleTimestampShouldBeRejectedAndAudited() throws Exception {
        String msgId = "a2-" + UUID.randomUUID();
        ResponseProbe probe = subscribeSingleResp();

        publishEnvelope(validSingleCmd(msgId, System.currentTimeMillis() - 400_000L, "default-tenant"));

        assertFalse(probe.awaitMessage(2), "超时 envelope 不应收到 single_resp");
        assertAuditEventually(msgId, "STALE_TIMESTAMP");
    }

    @Test
    void a3_duplicateMsgIdShouldOnlyProduceOneResponse() throws Exception {
        String msgId = "a3-" + UUID.randomUUID();
        ResponseProbe probe = subscribeSingleResp();
        byte[] envelope = validSingleCmd(msgId, System.currentTimeMillis(), "default-tenant");

        publishEnvelope(envelope);
        publishEnvelope(envelope);

        assertTrue(probe.awaitMessage(3), "首次合法指令应收到 single_resp");
        waitFor(Duration.ofMillis(800));
        assertEquals(1, probe.messageCount(), "重复 msgId 只能产生一次 single_resp");
        assertEquals("single_resp", probe.firstMessage().path("act").asText());
        assertEquals("cmd-" + msgId, probe.firstMessage().path("payload").path("cmdId").asText());
    }

    @Test
    void a4_tenantMismatchShouldBeRejectedAndAudited() throws Exception {
        String msgId = "a4-" + UUID.randomUUID();
        ResponseProbe probe = subscribeSingleResp();

        publishEnvelope(validSingleCmd(msgId, System.currentTimeMillis(), "other-tenant"));

        assertFalse(probe.awaitMessage(2), "tenant mismatch 不应收到 single_resp");
        assertAuditEventually(msgId, "TENANT_MISMATCH");
    }

    private ResponseProbe subscribeSingleResp() throws Exception {
        MqttClient subscriber = mqttClient("opendota-conformance-sub");
        ResponseProbe probe = new ResponseProbe();
        subscriber.connect(connectOptions());
        subscriber.subscribe(RESP_TOPIC, 1, (topic, message) -> {
            JsonNode envelope = mapper.readTree(new String(message.getPayload(), StandardCharsets.UTF_8));
            probe.capture(envelope);
        });
        return probe;
    }

    private void publishEnvelope(byte[] envelope) throws Exception {
        MqttClient publisher = mqttClient("opendota-conformance-pub");
        publisher.connect(connectOptions());
        publisher.publish(CMD_TOPIC, envelope, 1, false);
        publisher.disconnect();
    }

    private byte[] validSingleCmd(String msgId, long timestamp, String tenantId) throws Exception {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("msgId", msgId);
        envelope.put("timestamp", timestamp);
        envelope.put("vin", VIN);
        envelope.put("act", "single_cmd");
        envelope.set("payload", mapper.valueToTree(Map.of(
                "cmdId", "cmd-" + msgId,
                "channelId", "ch-conformance",
                "type", "raw_uds",
                "reqData", "22F190",
                "timeoutMs", 5000
        )));
        if (tenantId != null) {
            envelope.set("operator", mapper.valueToTree(Map.of(
                    "id", "operator-conformance",
                    "role", "engineer",
                    "tenantId", tenantId,
                    "ticketId", "TICKET-CONFORMANCE"
            )));
        }
        return mapper.writeValueAsBytes(envelope);
    }

    private void assertAuditEventually(String msgId, String action) throws Exception {
        JsonNode row = poll(Duration.ofSeconds(5), Duration.ofMillis(200), () -> queryAudit(msgId, action));
        assertNotNull(row, "未等到审计记录 action=" + action + " msgId=" + msgId);
        assertEquals(action, row.path("action").asText());
        assertEquals(msgId, row.path("msg_id").asText());
        assertEquals(VIN, row.path("vin").asText());
    }

    private JsonNode queryAudit(String msgId, String action) {
        return jdbcTemplate.query("""
                        SELECT msg_id, vin, action
                        FROM security_audit_log
                        WHERE msg_id = ? AND action = ?
                        ORDER BY id DESC
                        LIMIT 1
                        """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    ObjectNode row = mapper.createObjectNode();
                    row.put("msg_id", rs.getString("msg_id"));
                    row.put("vin", rs.getString("vin"));
                    row.put("action", rs.getString("action"));
                    return row;
                },
                msgId,
                action);
    }

    private <T> T poll(Duration timeout, Duration interval, ThrowingSupplier<T> supplier) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            T value = supplier.get();
            if (value != null) {
                return value;
            }
            waitFor(interval);
        }
        return supplier.get();
    }

    private void waitFor(Duration duration) throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(duration.toMillis());
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

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
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

        private int messageCount() {
            return envelopes.size();
        }

        private JsonNode firstMessage() {
            return envelopes.getFirst();
        }
    }
}

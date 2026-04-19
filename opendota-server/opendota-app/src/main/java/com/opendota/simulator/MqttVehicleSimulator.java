package com.opendota.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opendota.common.envelope.DiagAction;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Java 版车端 MQTT 模拟器(架构 §8 + v1.4 B1)。
 *
 * <p>职责:
 * <ol>
 *   <li>为 {@code SimulatorProperties.vehicles} 中每辆车建立独立 MQTT 连接</li>
 *   <li>订阅 {@code dota/v1/cmd/+/<VIN>} C2V Topic</li>
 *   <li>根据 {@code act} 分支到对应处理器</li>
 *   <li>把响应发回 {@code dota/v1/resp/<scene>/<VIN>} / {@code dota/v1/event/<scene>/<VIN>}</li>
 * </ol>
 *
 * <p>支持的 act:
 * <ul>
 *   <li>{@code channel_open} → 回 {@code channel_event opened}</li>
 *   <li>{@code channel_close} → 回 {@code channel_event closed}</li>
 *   <li>{@code single_cmd} → 查 CanMockResponder 回 {@code single_resp}</li>
 *   <li>{@code batch_cmd} → 逐步执行回 {@code batch_resp}</li>
 *   <li>{@code schedule_set} → 回 {@code task_ack accepted},周期触发由后台线程模拟</li>
 *   <li>{@code queue_query} → 回 {@code queue_status}</li>
 *   <li>{@code task_cancel} / {@code queue_delete} → 回 {@code task_ack accepted} 或
 *       {@code cancel_rejected}(不可中断宏场景,v1.4 A2)</li>
 * </ul>
 *
 * <p>**生产禁用**:{@code @ConditionalOnProperty} 保护,默认 enabled=false。
 */
public class MqttVehicleSimulator implements MqttCallback {

    private static final Logger log = LoggerFactory.getLogger(MqttVehicleSimulator.class);

    private final SimulatorProperties props;
    private final SimulatorProperties.VehicleProfile profile;
    private final CanMockResponder canResponder;
    private final ObjectMapper mapper;

    private MqttClient mqttClient;

    public MqttVehicleSimulator(SimulatorProperties props,
                                SimulatorProperties.VehicleProfile profile,
                                CanMockResponder canResponder,
                                ObjectMapper mapper) {
        this.props = props;
        this.profile = profile;
        this.canResponder = canResponder;
        this.mapper = mapper;
    }

    @PostConstruct
    public void start() throws MqttException {
        if (!profile.isStartOnline()) {
            log.info("Simulator vin={} 启动但保持离线(startOnline=false),需要手动调用 connect()", profile.getVin());
            return;
        }
        connect();
    }

    public synchronized void connect() throws MqttException {
        if (mqttClient != null && mqttClient.isConnected()) {
            return;
        }
        String clientId = "sim-" + profile.getVin();
        mqttClient = new MqttClient(props.getBrokerUrl(), clientId, new MemoryPersistence());

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(false);
        opts.setAutomaticReconnect(true);
        opts.setConnectionTimeout(10);
        opts.setKeepAliveInterval(60);
        opts.setUserName(profile.getVin()); // 按 §15.1.1 约定 username = VIN
        opts.setPassword(props.getMqttPassword().toCharArray());

        // Last Will: 异常断连时 EMQX Rule Engine 投 Kafka vehicle-lifecycle
        try {
            ObjectNode lwtPayload = mapper.createObjectNode();
            lwtPayload.put("vin", profile.getVin());
            lwtPayload.put("event", "abnormal_disconnect");
            lwtPayload.put("at", System.currentTimeMillis());
            opts.setWill(
                "dota/v1/event/lifecycle/" + profile.getVin(),
                mapper.writeValueAsBytes(lwtPayload),
                1, false);
        } catch (Exception e) {
            log.warn("构建 Last Will 失败(将不设 LWT)", e);
        }

        mqttClient.setCallback(this);
        mqttClient.connect(opts);

        // 订阅所有 C2V Topic
        mqttClient.subscribe("dota/v1/cmd/+/" + profile.getVin(), 1);

        log.info("🚗 Simulator connected: vin={} clientId={} broker={}",
                profile.getVin(), clientId, props.getBrokerUrl());

        // 启动握手:time_sync_response (self_report) + queue_status (boot_report)
        publishSelfReport();
        publishBootQueueStatus();
    }

    @PreDestroy
    public void stop() {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
                mqttClient.close();
            }
        } catch (MqttException e) {
            log.warn("关闭 simulator 失败 vin={}", profile.getVin(), e);
        }
    }

    // ========== MqttCallback ==========

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("Simulator vin={} MQTT 断连", profile.getVin(), cause);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            JsonNode env = mapper.readTree(message.getPayload());
            DiagAction act = DiagAction.of(env.get("act").asText());
            log.info("📨 simulator vin={} 收到 C2V: topic={} act={} msgId={}",
                    profile.getVin(), topic, act.wireName(), env.get("msgId").asText());

            simulateLatency();

            switch (act) {
                case CHANNEL_OPEN -> handleChannelOpen(env);
                case CHANNEL_CLOSE -> handleChannelClose(env);
                case SINGLE_CMD -> handleSingleCmd(env);
                case BATCH_CMD -> handleBatchCmd(env);
                case SCHEDULE_SET -> handleScheduleSet(env);
                case SCRIPT_CMD -> handleScriptCmd(env);
                case QUEUE_QUERY -> handleQueueQuery(env);
                case TASK_CANCEL, QUEUE_DELETE -> handleCancel(env, act);
                case TIME_SYNC_REQUEST -> handleTimeSyncRequest(env);
                default -> log.warn("vin={} 未实现的 act: {}", profile.getVin(), act.wireName());
            }
        } catch (Exception e) {
            log.error("simulator 处理消息异常 topic={} payload={}", topic,
                    new String(message.getPayload()), e);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // QoS 1 投递确认,不做特殊处理
    }

    // ========== 业务处理 ==========

    private void handleChannelOpen(JsonNode env) {
        String channelId = env.path("payload").path("channelId").asText();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("channelId", channelId);
        payload.put("event", "opened");
        payload.put("status", 0);
        payload.put("currentSession", "0x01");
        payload.putNull("currentSecurityLevel");
        payload.put("msg", "Java 模拟器:通道已建立");
        publishEnvelope("dota/v1/event/channel/" + profile.getVin(),
                DiagAction.CHANNEL_EVENT, env.get("operator"), payload);
    }

    private void handleChannelClose(JsonNode env) {
        String channelId = env.path("payload").path("channelId").asText();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("channelId", channelId);
        payload.put("event", "closed");
        payload.put("status", 0);
        publishEnvelope("dota/v1/event/channel/" + profile.getVin(),
                DiagAction.CHANNEL_EVENT, env.get("operator"), payload);
    }

    private void handleSingleCmd(JsonNode env) {
        JsonNode p = env.path("payload");
        String cmdId = p.path("cmdId").asText();
        String channelId = p.path("channelId").asText();
        String reqData = p.path("reqData").asText().toLowerCase();

        String resData = canResponder.respond(reqData);
        if (resData == null) {
            // 3E 80 不回响应
            return;
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("cmdId", cmdId);
        payload.put("channelId", channelId);
        payload.put("status", resData.startsWith("7f") ? 3 : 0);
        payload.put("errorCode", resData.startsWith("7f") ? nrcOf(resData) : "");
        payload.put("resData", resData);
        payload.put("execDuration", ThreadLocalRandom.current().nextInt(50, 300));
        publishEnvelope("dota/v1/resp/single/" + profile.getVin(),
                DiagAction.SINGLE_RESP, env.get("operator"), payload);
    }

    private void handleBatchCmd(JsonNode env) {
        JsonNode p = env.path("payload");
        String taskId = p.path("taskId").asText();
        JsonNode steps = p.path("steps");

        var results = mapper.createArrayNode();
        boolean anyFail = false;
        boolean allFail = true;
        for (JsonNode step : steps) {
            ObjectNode r = mapper.createObjectNode();
            r.put("seqId", step.path("seqId").asInt());
            String type = step.path("type").asText();
            if ("raw_uds".equals(type)) {
                String resData = canResponder.respond(step.path("data").asText());
                if (resData != null) {
                    r.put("status", resData.startsWith("7f") ? 3 : 0);
                    r.put("resData", resData);
                }
            } else {
                // 宏指令:简化返回成功
                r.put("status", 0);
                r.put("msg", type + " 模拟成功");
            }
            if (r.path("status").asInt() == 0) allFail = false;
            else anyFail = true;
            results.add(r);
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("taskId", taskId);
        payload.put("overallStatus", allFail ? 2 : (anyFail ? 1 : 0));
        payload.put("taskDuration", ThreadLocalRandom.current().nextInt(500, 3000));
        payload.set("results", results);
        publishEnvelope("dota/v1/resp/batch/" + profile.getVin(),
                DiagAction.BATCH_RESP, env.get("operator"), payload);
    }

    private void handleScheduleSet(JsonNode env) {
        // 简化:返回 task_ack accepted。真实车端会入队并周期触发 execution_begin/end
        sendTaskAck(env, "accepted", null);
    }

    private void handleScriptCmd(JsonNode env) {
        // 简化:多 ECU 脚本也走 batch 类似的响应,但 Topic 用 resp/script
        JsonNode p = env.path("payload");
        String scriptId = p.path("scriptId").asText();

        ObjectNode payload = mapper.createObjectNode();
        payload.put("scriptId", scriptId);
        payload.put("overallStatus", 0);
        payload.put("scriptDuration", ThreadLocalRandom.current().nextInt(1000, 5000));

        var ecuResults = mapper.createArrayNode();
        for (JsonNode ecu : p.path("ecus")) {
            ObjectNode er = mapper.createObjectNode();
            er.put("ecuName", ecu.path("ecuName").asText());
            er.put("overallStatus", 0);
            er.put("ecuDuration", ThreadLocalRandom.current().nextInt(500, 2000));
            var stepResults = mapper.createArrayNode();
            for (JsonNode step : ecu.path("steps")) {
                ObjectNode sr = mapper.createObjectNode();
                sr.put("seqId", step.path("seqId").asInt());
                String resData = canResponder.respond(step.path("data").asText());
                sr.put("status", resData == null || resData.startsWith("7f") ? 3 : 0);
                if (resData != null) sr.put("resData", resData);
                stepResults.add(sr);
            }
            er.set("results", stepResults);
            ecuResults.add(er);
        }
        payload.set("ecuResults", ecuResults);

        publishEnvelope("dota/v1/resp/script/" + profile.getVin(),
                DiagAction.SCRIPT_RESP, env.get("operator"), payload);
    }

    private void handleQueueQuery(JsonNode env) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("queueSize", 0);
        payload.putNull("currentExecuting");
        payload.put("resourceState", "IDLE");
        ObjectNode states = mapper.createObjectNode();
        states.put("VCU", "IDLE");
        states.put("BMS", "IDLE");
        payload.set("resourceStates", states);
        payload.set("tasks", mapper.createArrayNode());
        publishEnvelope("dota/v1/resp/queue/" + profile.getVin(),
                DiagAction.QUEUE_STATUS, env.get("operator"), payload);
    }

    private void handleCancel(JsonNode env, DiagAction act) {
        // v1.4 A2 演示:若 taskId 关键字包含 "ota" 或 "sec",模拟不可中断,返回 cancel_rejected
        String taskId = env.path("payload").path("taskId").asText();
        if (taskId.contains("ota") || taskId.contains("flash") || taskId.contains("sec")) {
            sendTaskAck(env, "cancel_rejected", "NON_INTERRUPTIBLE");
        } else {
            sendTaskAck(env, "accepted", null);
        }
    }

    private void handleTimeSyncRequest(JsonNode env) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("direction", "ack");
        payload.put("vehicleRtcAt", System.currentTimeMillis() + profile.getClockDriftMs());
        payload.put("vehicleMonotonicMs", System.nanoTime() / 1_000_000);
        payload.put("rtcSource", "hardware_rtc");
        publishEnvelope("dota/v1/resp/time/" + profile.getVin(),
                DiagAction.TIME_SYNC_RESPONSE, env.get("operator"), payload);
    }

    private void sendTaskAck(JsonNode env, String status, String reason) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("taskId", env.path("payload").path("taskId").asText());
        payload.put("originalMsgId", env.path("msgId").asText());
        payload.put("status", status);
        if (reason != null) {
            payload.put("reason", reason);
            payload.put("safeRetryAfterMs", 15000);
        }
        payload.put("enqueuedAt", System.currentTimeMillis());
        publishEnvelope("dota/v1/ack/" + profile.getVin(),
                DiagAction.TASK_ACK, env.get("operator"), payload);
    }

    private void publishSelfReport() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("direction", "self_report");
        payload.put("vehicleRtcAt", System.currentTimeMillis() + profile.getClockDriftMs());
        payload.put("vehicleMonotonicMs", System.nanoTime() / 1_000_000);
        payload.put("rtcSource", "hardware_rtc");
        payload.put("agentVersion", "java-simulator-1.0");
        publishEnvelope("dota/v1/resp/time/" + profile.getVin(),
                DiagAction.TIME_SYNC_RESPONSE, null, payload);
    }

    private void publishBootQueueStatus() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("triggerReason", "boot_report");
        payload.put("queueSize", 0);
        payload.put("resourceState", "IDLE");
        payload.set("tasks", mapper.createArrayNode());
        publishEnvelope("dota/v1/resp/queue/" + profile.getVin(),
                DiagAction.QUEUE_STATUS, null, payload);
    }

    // ========== 辅助 ==========

    private void publishEnvelope(String topic, DiagAction act, JsonNode operator, JsonNode payload) {
        try {
            ObjectNode env = mapper.createObjectNode();
            env.put("msgId", UUID.randomUUID().toString());
            env.put("timestamp", System.currentTimeMillis());
            env.put("vin", profile.getVin());
            env.put("act", act.wireName());
            if (operator != null && !operator.isNull()) {
                env.set("operator", operator);
            }
            env.set("payload", payload);

            byte[] bytes = mapper.writeValueAsBytes(env);
            mqttClient.publish(topic, bytes, 1, false);
            log.debug("📤 simulator vin={} 上报 V2C: topic={} act={}", profile.getVin(), topic, act.wireName());
        } catch (Exception e) {
            log.error("simulator 发布失败 topic={}", topic, e);
        }
    }

    private void simulateLatency() {
        try {
            int delay = ThreadLocalRandom.current().nextInt(
                    props.getMockMinLatencyMs(), props.getMockMaxLatencyMs() + 1);
            Thread.sleep(delay);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String nrcOf(String hexResp) {
        // 7F <SID> <NRC>
        if (hexResp.length() < 6) return "NRC_10";
        return "NRC_" + hexResp.substring(4, 6).toUpperCase();
    }

    /** 供测试手动触发连/断,模拟离线场景。 */
    public synchronized void disconnectQuietly() {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
            }
        } catch (MqttException e) {
            log.warn("disconnect 失败", e);
        }
    }

    public String getVin() {
        return profile.getVin();
    }
}

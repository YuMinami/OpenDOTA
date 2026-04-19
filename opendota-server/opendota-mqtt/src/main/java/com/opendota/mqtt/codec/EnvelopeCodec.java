package com.opendota.mqtt.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.Operator;

import java.util.Objects;

/**
 * Envelope 序列化 / 反序列化(协议 §3.1)。
 *
 * <p>C2V 下行时调用 {@link #serialize(DiagMessage)} 把 {@link DiagMessage} 转成 UTF-8 bytes。
 *
 * <p>V2C 上行因 payload 类型随 {@code act} 变化,这里统一返回 {@code DiagMessage<JsonNode>} —— 业务侧
 * 的 {@code V2cListener} 按 {@code act} 自行把 {@link JsonNode} 再 {@code treeToValue} 成具体 payload 记录。
 * 这避免了 Jackson 在 record 泛型擦除场景下踩 {@code TypeReference} 的坑。
 */
public class EnvelopeCodec {

    private final ObjectMapper mapper;

    public EnvelopeCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "ObjectMapper 必填");
    }

    /** 序列化 C2V envelope。 */
    public byte[] serialize(DiagMessage<?> env) {
        try {
            return mapper.writeValueAsBytes(env);
        } catch (Exception e) {
            throw new IllegalStateException("envelope 序列化失败 msgId=" + env.msgId(), e);
        }
    }

    /** 反序列化 V2C envelope,payload 留为 {@link JsonNode}。 */
    public DiagMessage<JsonNode> deserialize(byte[] raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            String msgId = requireText(root, "msgId");
            long timestamp = root.path("timestamp").asLong();
            String vin = requireText(root, "vin");
            DiagAction act = DiagAction.of(requireText(root, "act"));
            Operator operator = root.hasNonNull("operator")
                    ? mapper.treeToValue(root.get("operator"), Operator.class)
                    : null;
            String traceparent = root.hasNonNull("traceparent") ? root.get("traceparent").asText() : null;
            JsonNode payload = root.hasNonNull("payload") ? root.get("payload") : mapper.nullNode();
            return new DiagMessage<>(msgId, timestamp, vin, act, operator, traceparent, payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("envelope 反序列化失败", e);
        }
    }

    /** 供 V2cListener 把具体 payload 节点转回自身 record 类型。 */
    public <T> T readPayload(JsonNode payloadNode, Class<T> type) {
        try {
            return mapper.treeToValue(payloadNode, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("payload 反序列化失败 type=" + type.getSimpleName(), e);
        }
    }

    private static String requireText(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || n.asText().isBlank()) {
            throw new IllegalArgumentException("envelope 字段缺失: " + field);
        }
        return n.asText();
    }
}

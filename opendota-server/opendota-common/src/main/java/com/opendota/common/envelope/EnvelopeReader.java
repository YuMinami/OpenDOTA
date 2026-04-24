package com.opendota.common.envelope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.payload.channel.ChannelClosePayload;
import com.opendota.common.payload.channel.ChannelEventPayload;
import com.opendota.common.payload.channel.ChannelOpenPayload;
import com.opendota.common.payload.channel.ChannelReadyPayload;
import com.opendota.common.payload.single.SingleCmdPayload;
import com.opendota.common.payload.single.SingleRespPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 两阶段 envelope 解析器。
 *
 * <p>先读取 {@code act},再根据 act 决定 payload 的目标类型。
 * 当前尚未补齐 DTO 的 act 会回落到 {@link JsonNode},后续 Phase 1 继续扩表即可。
 */
public class EnvelopeReader {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeReader.class);

    private final ObjectMapper mapper;
    private final Map<DiagAction, Class<?>> payloadTypeRegistry;

    public EnvelopeReader(ObjectMapper mapper) {
        this(mapper, defaultPayloadTypeRegistry());
    }

    public EnvelopeReader(ObjectMapper mapper, Map<DiagAction, Class<?>> payloadTypeRegistry) {
        this.mapper = Objects.requireNonNull(mapper, "ObjectMapper 必填");
        this.payloadTypeRegistry = Map.copyOf(Objects.requireNonNull(payloadTypeRegistry, "payloadTypeRegistry 必填"));
    }

    public DiagMessage<?> parse(byte[] raw) {
        ParsedEnvelope parsed = parseEnvelope(raw);
        Class<?> payloadType = payloadTypeRegistry.getOrDefault(parsed.act(), JsonNode.class);
        if (parsed.act() == DiagAction.UNKNOWN) {
            log.warn("遇到未知 act={},payload 将回退为 JsonNode", parsed.rawAct());
            payloadType = JsonNode.class;
        }
        return buildMessage(parsed, payloadType);
    }

    public <T> DiagMessage<T> parse(byte[] raw, Class<T> payloadType) {
        ParsedEnvelope parsed = parseEnvelope(raw);
        return buildMessage(parsed, payloadType);
    }

    public Map<DiagAction, Class<?>> payloadTypeRegistry() {
        return payloadTypeRegistry;
    }

    private ParsedEnvelope parseEnvelope(byte[] raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            String msgId = requireText(root, "msgId");
            long timestamp = root.path("timestamp").asLong();
            String vin = requireText(root, "vin");
            String rawAct = requireText(root, "act");
            DiagAction act = DiagAction.ofOrUnknown(rawAct);
            Operator operator = root.hasNonNull("operator")
                    ? mapper.treeToValue(root.get("operator"), Operator.class)
                    : null;
            String traceparent = root.hasNonNull("traceparent") ? root.get("traceparent").asText() : null;
            JsonNode payload = root.has("payload") ? root.get("payload") : mapper.nullNode();
            return new ParsedEnvelope(msgId, timestamp, vin, rawAct, act, operator, traceparent, payload);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("envelope 解析失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> DiagMessage<T> buildMessage(ParsedEnvelope parsed, Class<?> payloadType) {
        try {
            T payload;
            if (payloadType == JsonNode.class || parsed.payload() == null || parsed.payload().isNull()) {
                payload = (T) parsed.payload();
            } else {
                payload = (T) mapper.treeToValue(parsed.payload(), payloadType);
            }
            return new DiagMessage<>(
                    parsed.msgId(),
                    parsed.timestamp(),
                    parsed.vin(),
                    parsed.act(),
                    parsed.operator(),
                    parsed.traceparent(),
                    payload
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("payload 解析失败 type=" + payloadType.getSimpleName(), e);
        }
    }

    private static Map<DiagAction, Class<?>> defaultPayloadTypeRegistry() {
        Map<DiagAction, Class<?>> registry = new EnumMap<>(DiagAction.class);
        registry.put(DiagAction.CHANNEL_OPEN, ChannelOpenPayload.class);
        registry.put(DiagAction.CHANNEL_CLOSE, ChannelClosePayload.class);
        registry.put(DiagAction.CHANNEL_EVENT, ChannelEventPayload.class);
        registry.put(DiagAction.CHANNEL_READY, ChannelReadyPayload.class);
        registry.put(DiagAction.SINGLE_CMD, SingleCmdPayload.class);
        registry.put(DiagAction.SINGLE_RESP, SingleRespPayload.class);
        return registry;
    }

    private static String requireText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("envelope 字段缺失: " + field);
        }
        return value.asText();
    }

    private record ParsedEnvelope(
            String msgId,
            long timestamp,
            String vin,
            String rawAct,
            DiagAction act,
            Operator operator,
            String traceparent,
            JsonNode payload) {
    }
}

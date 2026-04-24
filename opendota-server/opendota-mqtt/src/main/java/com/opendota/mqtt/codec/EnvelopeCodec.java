package com.opendota.mqtt.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.EnvelopeReader;
import com.opendota.common.envelope.EnvelopeWriter;

import java.util.Objects;

/**
 * Envelope 兼容编解码器。
 *
 * <p>Step 1.5 起新链路统一走 {@link EnvelopeReader} + {@link EnvelopeWriter}。本类仅保留给旧调用点
 * 过渡使用,避免在一次提交里同时迁完所有注入点。
 */
@Deprecated(forRemoval = false)
public class EnvelopeCodec {

    private final ObjectMapper mapper;
    private final EnvelopeReader envelopeReader;
    private final EnvelopeWriter envelopeWriter;

    public EnvelopeCodec(ObjectMapper mapper) {
        this(mapper, new EnvelopeReader(mapper), new EnvelopeWriter(mapper));
    }

    public EnvelopeCodec(ObjectMapper mapper, EnvelopeReader envelopeReader, EnvelopeWriter envelopeWriter) {
        this.mapper = Objects.requireNonNull(mapper, "ObjectMapper 必填");
        this.envelopeReader = Objects.requireNonNull(envelopeReader, "EnvelopeReader 必填");
        this.envelopeWriter = Objects.requireNonNull(envelopeWriter, "EnvelopeWriter 必填");
    }

    /** 序列化 C2V envelope。 */
    public byte[] serialize(DiagMessage<?> env) {
        try {
            return envelopeWriter.write(env);
        } catch (Exception e) {
            throw new IllegalStateException("envelope 序列化失败 msgId=" + env.msgId(), e);
        }
    }

    /** 反序列化 V2C envelope,payload 保持旧接口兼容的 {@link JsonNode}。 */
    public DiagMessage<JsonNode> deserialize(byte[] raw) {
        return envelopeReader.parse(raw, JsonNode.class);
    }

    /** 供遗留调用点把具体 payload 节点转回自身 record 类型。 */
    public <T> T readPayload(JsonNode payloadNode, Class<T> type) {
        try {
            return mapper.treeToValue(payloadNode, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("payload 反序列化失败 type=" + type.getSimpleName(), e);
        }
    }
}

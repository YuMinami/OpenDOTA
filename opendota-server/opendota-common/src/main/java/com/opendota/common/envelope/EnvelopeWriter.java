package com.opendota.common.envelope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 统一信封写出器。
 *
 * <p>封装 MQTT envelope 序列化与 diagPayload hash 计算,供后续 opendota-mqtt / opendota-task 复用。
 */
public class EnvelopeWriter {

    private final ObjectMapper mapper;
    private final DiagPayloadCanonicalizer payloadCanonicalizer;

    public EnvelopeWriter(ObjectMapper mapper) {
        this(mapper, new DiagPayloadCanonicalizer(mapper));
    }

    public EnvelopeWriter(ObjectMapper mapper, DiagPayloadCanonicalizer payloadCanonicalizer) {
        this.mapper = Objects.requireNonNull(mapper, "ObjectMapper 必填");
        this.payloadCanonicalizer = Objects.requireNonNull(payloadCanonicalizer, "payloadCanonicalizer 必填");
    }

    public byte[] write(DiagMessage<?> envelope) {
        try {
            return mapper.writeValueAsBytes(Objects.requireNonNull(envelope, "envelope 必填"));
        } catch (Exception e) {
            throw new IllegalArgumentException("envelope 序列化失败", e);
        }
    }

    public String writeString(DiagMessage<?> envelope) {
        return new String(write(envelope), StandardCharsets.UTF_8);
    }

    public JsonNode normalizePayload(Object payload) {
        return payloadCanonicalizer.normalize(payload);
    }

    public String canonicalizePayload(Object payload) {
        return payloadCanonicalizer.canonicalize(payload);
    }

    public String payloadHash(Object payload) {
        return payloadCanonicalizer.payloadHash(payload);
    }
}

package com.opendota.common.envelope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.opendota.common.payload.common.Transport;
import com.opendota.common.util.HexUtils;
import org.erdtman.jcs.JsonCanonicalizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * diagPayload 规范化与 payloadHash 计算器。
 *
 * <p>实现遵循 {@code doc/schema/payload_hash_canonicalization.md}:
 * 先做 OpenDOTA 业务预处理,再走 RFC 8785 JCS,最后计算 SHA-256。
 */
public class DiagPayloadCanonicalizer {

    private static final Pattern HEX_WITH_PREFIX = Pattern.compile("(?i)^0x[0-9a-f]+$");
    private static final Pattern HEX_PLAIN = Pattern.compile("(?i)^[0-9a-f]+$");
    private static final Set<String> ADDRESS_FIELDS = Set.of("txId", "rxId", "activationType");
    private static final Set<String> HEX_FIELDS = Set.of(
            "txId", "rxId", "activationType", "data", "reqData", "resData", "seed", "key");

    private final ObjectMapper mapper;

    public DiagPayloadCanonicalizer(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "ObjectMapper 必填");
    }

    public ObjectNode normalize(Object payload) {
        JsonNode root = payload instanceof JsonNode node ? node.deepCopy() : mapper.valueToTree(payload);
        if (root == null || root.isNull()) {
            throw new IllegalArgumentException("diagPayload 必填");
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("diagPayload 必须为 JSON object");
        }
        return normalizeObject((ObjectNode) root);
    }

    public String canonicalize(Object payload) {
        ObjectNode normalized = normalize(payload);
        try {
            String json = mapper.writeValueAsString(normalized);
            return new JsonCanonicalizer(json).getEncodedString();
        } catch (Exception e) {
            throw new IllegalArgumentException("diagPayload JCS 规范化失败", e);
        }
    }

    public String payloadHash(Object payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalize(payload).getBytes(StandardCharsets.UTF_8));
            return HexUtils.toHex(digest);
        } catch (Exception e) {
            throw new IllegalArgumentException("payloadHash 计算失败", e);
        }
    }

    private ObjectNode normalizeObject(ObjectNode objectNode) {
        ObjectNode normalized = mapper.createObjectNode();
        objectNode.fields().forEachRemaining(entry ->
                normalized.set(entry.getKey(), normalizeNode(entry.getValue(), entry.getKey())));
        applyDefaults(normalized);
        return normalized;
    }

    private JsonNode normalizeNode(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) {
            return mapper.nullNode();
        }
        if (node.isObject()) {
            return normalizeObject((ObjectNode) node);
        }
        if (node.isArray()) {
            return normalizeArray((ArrayNode) node, fieldName);
        }
        if (node.isTextual()) {
            return normalizeText(node.textValue(), fieldName);
        }
        return node.deepCopy();
    }

    private ArrayNode normalizeArray(ArrayNode arrayNode, String fieldName) {
        List<JsonNode> items = new ArrayList<>(arrayNode.size());
        for (JsonNode item : arrayNode) {
            items.add(normalizeNode(item, fieldName));
        }
        if ("ecuScope".equals(fieldName)) {
            items.sort(Comparator.comparing(JsonNode::asText));
        }
        ArrayNode normalized = mapper.createArrayNode();
        items.forEach(normalized::add);
        return normalized;
    }

    private JsonNode normalizeText(String value, String fieldName) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        if (fieldName != null && HEX_FIELDS.contains(fieldName) && isHexLike(normalized)) {
            normalized = ADDRESS_FIELDS.contains(fieldName)
                    ? HexUtils.normalizeAddr(normalized)
                    : normalized.toLowerCase(Locale.ROOT);
        }
        return TextNode.valueOf(normalized);
    }

    private void applyDefaults(ObjectNode node) {
        if (isBatchLike(node)) {
            if (!node.has("transport")) {
                node.put("transport", Transport.UDS_ON_CAN.wireName());
            }
            if (!node.has("strategy")) {
                node.put("strategy", 1);
            }
        }
        if (isStep(node) && !node.has("timeoutMs")) {
            node.put("timeoutMs", 3000);
        }
        if (isDoipConfig(node)) {
            if (!node.has("ecuPort")) {
                node.put("ecuPort", 13400);
            }
            if (!node.has("activationType")) {
                node.put("activationType", "0x00");
            }
        }
        if (isScriptPayload(node) && !node.has("priority")) {
            node.put("priority", 5);
        }
    }

    private static boolean isHexLike(String value) {
        return HEX_WITH_PREFIX.matcher(value).matches() || HEX_PLAIN.matcher(value).matches();
    }

    private static boolean isBatchLike(ObjectNode node) {
        return node.has("ecuName")
                && node.has("ecuScope")
                && node.has("txId")
                && node.has("rxId")
                && node.has("steps");
    }

    private static boolean isStep(ObjectNode node) {
        return node.has("seqId") && node.has("type");
    }

    private static boolean isDoipConfig(ObjectNode node) {
        return node.has("ecuIp")
                || node.has("ecuPort")
                || node.has("activationType")
                || node.has("workstationId");
    }

    private static boolean isScriptPayload(ObjectNode node) {
        return node.has("scriptId")
                && node.has("executionMode")
                && node.has("globalTimeoutMs")
                && node.has("ecus");
    }
}

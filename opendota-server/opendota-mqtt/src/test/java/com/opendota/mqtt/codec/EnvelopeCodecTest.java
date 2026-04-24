package com.opendota.mqtt.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.EnvelopeReader;
import com.opendota.common.envelope.EnvelopeWriter;
import com.opendota.common.envelope.Operator;
import com.opendota.common.envelope.OperatorRole;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SuppressWarnings("deprecation")
class EnvelopeCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final EnvelopeCodec codec = new EnvelopeCodec(
            mapper,
            new EnvelopeReader(mapper),
            new EnvelopeWriter(mapper)
    );

    @Test
    void deserializeKeepsLegacyJsonNodePayload() {
        DiagMessage<JsonNode> envelope = codec.deserialize("""
                {
                  "msgId":"msg-001",
                  "timestamp":1713301200000,
                  "vin":"LSVWA234567890123",
                  "act":"single_resp",
                  "payload":{
                    "cmdId":"cmd-1",
                    "channelId":"ch-1",
                    "status":0,
                    "resData":"62F190"
                  }
                }
                """.getBytes(StandardCharsets.UTF_8));

        assertEquals(DiagAction.SINGLE_RESP, envelope.act());
        assertInstanceOf(JsonNode.class, envelope.payload());
        assertEquals("cmd-1", envelope.payload().get("cmdId").asText());
    }

    @Test
    void serializeDelegatesToEnvelopeWriterFormat() throws Exception {
        DiagMessage<Map<String, Object>> envelope = DiagMessage.c2v(
                "LSVWA234567890123",
                DiagAction.SINGLE_CMD,
                new Operator("operator-1", OperatorRole.ADMIN, "tenant-1", null),
                Map.of("cmdId", "cmd-1", "channelId", "ch-1", "type", "raw_uds", "reqData", "22F190", "timeoutMs", 5000)
        );

        JsonNode expected = mapper.readTree(new EnvelopeWriter(mapper).write(envelope));
        JsonNode actual = mapper.readTree(codec.serialize(envelope));

        assertEquals(expected, actual);
    }
}

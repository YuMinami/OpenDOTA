package com.opendota.common.envelope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DiagPayloadCanonicalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DiagPayloadCanonicalizer canonicalizer = new DiagPayloadCanonicalizer(mapper);

    @Test
    void payloadHashMatchesFrozenVectors() throws Exception {
        JsonNode root = readVectors();
        for (JsonNode vector : root.withArray("vectors")) {
            String id = vector.get("id").asText();
            String expectedHash = vector.get("expectedHash").asText();

            String actualHash = canonicalizer.payloadHash(vector.get("payload"));
            assertEquals(expectedHash, actualHash, id + " primary");

            JsonNode equivalentPayload = vector.get("equivalentPayload");
            if (equivalentPayload != null && !equivalentPayload.isNull()) {
                assertEquals(expectedHash, canonicalizer.payloadHash(equivalentPayload), id + " equivalent");
            }
        }
    }

    @Test
    void normalizeExpandsScriptAndDoipDefaults() throws Exception {
        JsonNode raw = mapper.readTree("""
                {
                  "payloadType":"script",
                  "scriptId":"script-1",
                  "executionMode":"parallel",
                  "globalTimeoutMs":120000,
                  "ecus":[
                    {
                      "ecuName":"BMS",
                      "ecuScope":["VCU","BMS"],
                      "transport":"UDS_ON_DOIP",
                      "txId":"0X18DA10F1",
                      "rxId":"0x18DAF110",
                      "doipConfig":{"ecuIp":"192.168.1.10"},
                      "steps":[
                        {"seqId":1,"type":"raw_uds","data":"22F190"}
                      ]
                    }
                  ]
                }
                """);

        JsonNode normalized = canonicalizer.normalize(raw);
        JsonNode ecu = normalized.get("ecus").get(0);
        JsonNode doipConfig = ecu.get("doipConfig");
        JsonNode firstStep = ecu.get("steps").get(0);

        assertEquals(5, normalized.get("priority").asInt());
        assertEquals("BMS", ecu.get("ecuScope").get(0).asText());
        assertEquals("VCU", ecu.get("ecuScope").get(1).asText());
        assertEquals("0x18da10f1", ecu.get("txId").asText());
        assertEquals("0x18daf110", ecu.get("rxId").asText());
        assertEquals(13400, doipConfig.get("ecuPort").asInt());
        assertEquals("0x00", doipConfig.get("activationType").asText());
        assertEquals("22f190", firstStep.get("data").asText());
        assertEquals(3000, firstStep.get("timeoutMs").asInt());
    }

    private JsonNode readVectors() throws Exception {
        try (InputStream inputStream = getClass().getResourceAsStream("/payload-hash-vectors.json")) {
            assertNotNull(inputStream, "payload-hash-vectors.json 不存在");
            return mapper.readTree(inputStream);
        }
    }
}

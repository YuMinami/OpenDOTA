package com.opendota.common.envelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.payload.single.SingleCmdPayload;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvelopeWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final EnvelopeWriter writer = new EnvelopeWriter(mapper);
    private final EnvelopeReader reader = new EnvelopeReader(mapper);

    @Test
    void writeRoundTripsThroughEnvelopeReader() {
        DiagMessage<SingleCmdPayload> envelope = new DiagMessage<>(
                "msg-001",
                1713301200000L,
                "LSVWA234567890123",
                DiagAction.SINGLE_CMD,
                new Operator("eng-1", OperatorRole.ENGINEER, "tenant-a", "DIAG-1"),
                "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01",
                new SingleCmdPayload("cmd-1", "ch-1", "raw_uds", "22F190", null)
        );

        byte[] raw = writer.write(envelope);
        DiagMessage<SingleCmdPayload> parsed = reader.parse(raw, SingleCmdPayload.class);

        assertEquals(DiagAction.SINGLE_CMD, parsed.act());
        assertEquals("cmd-1", parsed.payload().cmdId());
        assertEquals("22F190", parsed.payload().reqData());
        assertTrue(new String(raw, StandardCharsets.UTF_8).contains("\"act\":\"single_cmd\""));
    }
}

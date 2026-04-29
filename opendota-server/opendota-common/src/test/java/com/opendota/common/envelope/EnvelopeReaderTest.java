package com.opendota.common.envelope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.payload.channel.ChannelClosePayload;
import com.opendota.common.payload.channel.ChannelEventPayload;
import com.opendota.common.payload.channel.ChannelOpenPayload;
import com.opendota.common.payload.channel.ChannelReadyPayload;
import com.opendota.common.payload.single.SingleCmdPayload;
import com.opendota.common.payload.batch.BatchRespPayload;
import com.opendota.common.payload.script.ScriptRespPayload;
import com.opendota.common.payload.single.SingleRespPayload;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvelopeReaderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final EnvelopeReader reader = new EnvelopeReader(mapper);

    @Test
    void autoParseRoutesKnownActPayloadsToTypedRecords() {
        assertInstanceOf(ChannelOpenPayload.class, reader.parse(envelope("channel_open", "{}")).payload());
        assertInstanceOf(ChannelClosePayload.class, reader.parse(envelope("channel_close", "{}")).payload());
        assertInstanceOf(ChannelEventPayload.class, reader.parse(envelope("channel_event", "{}")).payload());
        assertInstanceOf(ChannelReadyPayload.class, reader.parse(envelope("channel_ready", "{}")).payload());
        assertInstanceOf(SingleCmdPayload.class, reader.parse(envelope("single_cmd", "{}")).payload());
        assertInstanceOf(SingleRespPayload.class, reader.parse(envelope("single_resp", "{}")).payload());
        assertInstanceOf(BatchRespPayload.class, reader.parse(envelope("batch_resp", "{}")).payload());
        assertInstanceOf(ScriptRespPayload.class, reader.parse(envelope("script_resp", "{}")).payload());
    }

    @Test
    void autoParseFallsBackToJsonNodeForUnregisteredActs() {
        for (String act : new String[]{
                "batch_cmd", "schedule_set", "schedule_cancel", "schedule_resp",
                "script_cmd", "queue_query", "queue_delete", "queue_pause",
                "queue_resume", "queue_status", "queue_reject", "task_pause", "task_resume",
                "task_cancel", "task_query", "task_ack", "condition_fired", "execution_begin",
                "execution_end", "time_sync_request", "time_sync_response", "signal_catalog_push",
                "signal_catalog_ack"
        }) {
            assertInstanceOf(JsonNode.class, reader.parse(envelope(act, "{\"k\":\"v\"}")).payload(), act);
        }
    }

    @Test
    void explicitParseUsesProvidedPayloadType() {
        DiagMessage<SingleCmdPayload> parsed = reader.parse(
                envelope("single_cmd", """
                        {"cmdId":"cmd-1","channelId":"ch-1","type":"raw_uds","reqData":"22F190","timeoutMs":5000}
                        """),
                SingleCmdPayload.class
        );

        assertEquals(DiagAction.SINGLE_CMD, parsed.act());
        assertEquals("cmd-1", parsed.payload().cmdId());
        assertEquals("ch-1", parsed.payload().channelId());
    }

    @Test
    void unknownActReturnsUnknownEnumAndJsonNodePayload() {
        DiagMessage<?> parsed = reader.parse(envelope("not_defined_act", "{\"foo\":\"bar\"}"));

        assertEquals(DiagAction.UNKNOWN, parsed.act());
        assertInstanceOf(JsonNode.class, parsed.payload());
    }

    @Test
    void missingMsgIdThrowsIllegalArgumentException() {
        byte[] raw = """
                {"timestamp":1,"vin":"LSVWA234567890123","act":"single_cmd","payload":{}}
                """.getBytes(StandardCharsets.UTF_8);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> reader.parse(raw));
        assertEquals("envelope 字段缺失: msgId", ex.getMessage());
    }

    @Test
    void registryExposesTypedEntries() {
        Map<DiagAction, Class<?>> registry = reader.payloadTypeRegistry();

        assertEquals(ChannelOpenPayload.class, registry.get(DiagAction.CHANNEL_OPEN));
        assertEquals(SingleCmdPayload.class, registry.get(DiagAction.SINGLE_CMD));
    }

    private byte[] envelope(String act, String payloadJson) {
        return ("""
                {
                  "msgId":"msg-001",
                  "timestamp":1713301200000,
                  "vin":"LSVWA234567890123",
                  "act":"%s",
                  "payload":%s
                }
                """.formatted(act, payloadJson)).getBytes(StandardCharsets.UTF_8);
    }
}

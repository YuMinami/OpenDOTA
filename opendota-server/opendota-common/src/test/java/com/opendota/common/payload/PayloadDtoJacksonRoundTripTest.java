package com.opendota.common.payload;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.Operator;
import com.opendota.common.envelope.OperatorRole;
import com.opendota.common.payload.common.BatchDiagPayload;
import com.opendota.common.payload.common.DoipConfig;
import com.opendota.common.payload.common.EcuScope;
import com.opendota.common.payload.common.Step;
import com.opendota.common.payload.common.Transport;
import com.opendota.common.payload.single.SingleCmdPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PayloadDtoJacksonRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void diagMessageWithSingleCmdPayloadRoundTripsWithoutFieldLoss() throws Exception {
        DiagMessage<SingleCmdPayload> original = new DiagMessage<>(
                "msg-001",
                1713301200000L,
                "LSVWA234567890123",
                DiagAction.SINGLE_CMD,
                new Operator("eng-12345", OperatorRole.ENGINEER, "chery-hq", "DIAG-001"),
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                new SingleCmdPayload("cmd-001", "ch-001", "raw_uds", "22F190", 5000)
        );

        String json = mapper.writeValueAsString(original);
        DiagMessage<SingleCmdPayload> restored = mapper.readValue(
                json,
                new TypeReference<>() {
                });

        assertEquals(original.msgId(), restored.msgId());
        assertEquals(original.timestamp(), restored.timestamp());
        assertEquals(original.vin(), restored.vin());
        assertEquals(original.act(), restored.act());
        assertNotNull(restored.operator());
        assertEquals(original.operator().id(), restored.operator().id());
        assertEquals(original.operator().role(), restored.operator().role());
        assertEquals(original.operator().tenantId(), restored.operator().tenantId());
        assertEquals(original.operator().ticketId(), restored.operator().ticketId());
        assertNotNull(restored.payload());
        assertEquals(original.payload().cmdId(), restored.payload().cmdId());
        assertEquals(original.payload().channelId(), restored.payload().channelId());
        assertEquals(original.payload().type(), restored.payload().type());
        assertEquals(original.payload().reqData(), restored.payload().reqData());
        assertEquals(original.payload().timeoutMs(), restored.payload().timeoutMs());
    }

    @Test
    void ecuScopeNormalizeSortsLexicographically() {
        assertEquals(List.of("BMS", "GW", "VCU"), EcuScope.normalize(List.of("VCU", "GW", "BMS")));
    }

    @Test
    void batchDiagPayloadAppliesSchemaDefaultsAndStableOrdering() throws Exception {
        BatchDiagPayload payload = new BatchDiagPayload(
                null,
                "BMS",
                List.of("VCU", "BMS"),
                null,
                "0x7E3",
                "0x7EB",
                new DoipConfig("169.254.100.10", null, null, null),
                null,
                List.of(new Step(1, "raw_uds", "190209", null, null))
        );

        String json = mapper.writeValueAsString(payload);
        BatchDiagPayload restored = mapper.readValue(json, BatchDiagPayload.class);

        assertEquals("batch", restored.payloadType());
        assertEquals(List.of("BMS", "VCU"), restored.ecuScope());
        assertEquals(Transport.UDS_ON_CAN, restored.transport());
        assertEquals(1, restored.strategy());
        assertNotNull(restored.doipConfig());
        assertEquals(13400, restored.doipConfig().ecuPort());
        assertEquals("0x00", restored.doipConfig().activationType());
        assertNotNull(restored.steps());
        assertEquals(1, restored.steps().size());
        assertEquals(3000, restored.steps().getFirst().timeoutMs());
    }
}

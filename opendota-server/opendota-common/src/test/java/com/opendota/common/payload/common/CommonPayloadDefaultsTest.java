package com.opendota.common.payload.common;

import com.opendota.common.envelope.Operator;
import com.opendota.common.envelope.OperatorRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonPayloadDefaultsTest {

    @Test
    void operatorSystemBuildsSystemContextForTenant() {
        Operator operator = Operator.system("tenant-a");

        assertEquals("system", operator.id());
        assertEquals(OperatorRole.SYSTEM, operator.role());
        assertEquals("tenant-a", operator.tenantId());
        assertNull(operator.ticketId());
    }

    @Test
    void operatorRoleOfAndCanForcePreemptFollowWireContract() {
        assertEquals(OperatorRole.ADMIN, OperatorRole.of("admin"));
        assertEquals("engineer", OperatorRole.ENGINEER.wireName());
        assertTrue(OperatorRole.SENIOR_ENGINEER.canForcePreempt());
        assertTrue(OperatorRole.ADMIN.canForcePreempt());
        assertEquals(
                "未知 OperatorRole: root",
                assertThrows(IllegalArgumentException.class, () -> OperatorRole.of("root")).getMessage()
        );
    }

    @Test
    void transportOfAcceptsCaseInsensitiveWireNameAndRejectsUnknownValues() {
        assertEquals(Transport.UDS_ON_CAN, Transport.of("uds_on_can"));
        assertEquals(Transport.UDS_ON_DOIP, Transport.of("UDS_ON_DOIP"));
        assertNull(Transport.of(null));
        assertEquals(
                "未知 Transport: LIN",
                assertThrows(IllegalArgumentException.class, () -> Transport.of("LIN")).getMessage()
        );
    }

    @Test
    void scriptDiagPayloadAppliesDefaultsAndCopiesInputList() {
        EcuBlock ecu = new EcuBlock("VCU", List.of("VCU"), null, null, null, null, null, null);
        List<EcuBlock> ecus = new java.util.ArrayList<>(List.of(ecu));
        ScriptDiagPayload payload = new ScriptDiagPayload(null, "script-001", "serial", 30_000, null, ecus);

        ecus.clear();

        assertEquals("script", payload.payloadType());
        assertEquals(5, payload.priority());
        assertEquals(1, payload.ecus().size());
        assertEquals("VCU", payload.ecus().getFirst().ecuName());
    }

    @Test
    void ecuBlockAppliesDefaultsAndNormalizesCollections() {
        Step step = new Step(2, "raw_uds", "22F190", 5_000, null);
        EcuBlock block = new EcuBlock(
                "VCU",
                List.of("BMS", "VCU"),
                null,
                "0x7E0",
                "0x7E8",
                null,
                null,
                List.of(step)
        );

        assertEquals(List.of("BMS", "VCU"), block.ecuScope());
        assertEquals(Transport.UDS_ON_CAN, block.transport());
        assertEquals(1, block.strategy());
        assertEquals(1, block.steps().size());
        assertEquals(step, block.steps().getFirst());
    }
}

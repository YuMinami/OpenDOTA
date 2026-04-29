package com.opendota.common.security;

import com.opendota.common.envelope.Operator;
import com.opendota.common.envelope.OperatorRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OperatorPrincipalTest {

    @Test
    void getName_returnsOperatorId() {
        OperatorPrincipal p = new OperatorPrincipal("op-001", "tenant-a", OperatorRole.ENGINEER, "jti-001");
        assertEquals("op-001", p.getName());
    }

    @Test
    void toOperator_convertsCorrectly() {
        OperatorPrincipal p = new OperatorPrincipal("op-001", "tenant-a", OperatorRole.ENGINEER, "jti-001");
        Operator op = p.toOperator("ticket-123");

        assertEquals("op-001", op.id());
        assertEquals(OperatorRole.ENGINEER, op.role());
        assertEquals("tenant-a", op.tenantId());
        assertEquals("ticket-123", op.ticketId());
    }

    @Test
    void toOperator_nullTicketId() {
        OperatorPrincipal p = new OperatorPrincipal("op-001", "tenant-a", OperatorRole.ADMIN, "jti-001");
        Operator op = p.toOperator(null);
        assertNull(op.ticketId());
    }
}

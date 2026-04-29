package com.opendota.security.rbac;

import com.opendota.common.envelope.OperatorRole;
import com.opendota.common.security.OperatorPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.*;

class RbacServiceTest {

    private RbacService rbac;

    @BeforeEach
    void setUp() {
        rbac = new RbacService();
    }

    private Authentication auth(OperatorRole role) {
        OperatorPrincipal principal = new OperatorPrincipal("op-001", "tenant-a", role, "jti-001");
        return new UsernamePasswordAuthenticationToken(principal, null);
    }

    @Test
    void canExecute_nullAuth_returnsFalse() {
        assertFalse(rbac.canExecute("channel_open", null, null, null));
    }

    @Test
    void canExecute_systemRole_bypassesAll() {
        assertTrue(rbac.canExecute("firmware_flash", auth(OperatorRole.SYSTEM), null, null));
        assertTrue(rbac.canExecute("channel_open", auth(OperatorRole.SYSTEM), null, null));
    }

    @Test
    void canExecute_viewer_canRead() {
        assertTrue(rbac.canExecute("odx_query", auth(OperatorRole.VIEWER), null, null));
        assertTrue(rbac.canExecute("sse_subscribe", auth(OperatorRole.VIEWER), null, null));
    }

    @Test
    void canExecute_viewer_cannotDiag() {
        assertFalse(rbac.canExecute("channel_open", auth(OperatorRole.VIEWER), null, null));
        assertFalse(rbac.canExecute("single_cmd", auth(OperatorRole.VIEWER), null, null));
    }

    @Test
    void canExecute_engineer_canDiag() {
        assertTrue(rbac.canExecute("channel_open", auth(OperatorRole.ENGINEER), null, null));
        assertTrue(rbac.canExecute("single_cmd", auth(OperatorRole.ENGINEER), null, null));
        assertTrue(rbac.canExecute("batch_cmd", auth(OperatorRole.ENGINEER), null, null));
    }

    @Test
    void canExecute_engineer_cannotAdmin() {
        assertFalse(rbac.canExecute("firmware_flash", auth(OperatorRole.ENGINEER), null, null));
        assertFalse(rbac.canExecute("odx_import", auth(OperatorRole.ENGINEER), null, null));
    }

    @Test
    void canExecute_seniorEngineer_canForcePreempt() {
        assertTrue(rbac.canExecute("force_preempt", auth(OperatorRole.SENIOR_ENGINEER), null, null));
    }

    @Test
    void canExecute_engineer_cannotForcePreempt() {
        assertFalse(rbac.canExecute("force_preempt", auth(OperatorRole.ENGINEER), null, null));
    }

    @Test
    void canExecute_admin_canDoEverything() {
        assertTrue(rbac.canExecute("firmware_flash", auth(OperatorRole.ADMIN), null, null));
        assertTrue(rbac.canExecute("odx_import", auth(OperatorRole.ADMIN), null, null));
        assertTrue(rbac.canExecute("operator_manage", auth(OperatorRole.ADMIN), null, null));
        assertTrue(rbac.canExecute("audit_export", auth(OperatorRole.ADMIN), null, null));
        assertTrue(rbac.canExecute("channel_open", auth(OperatorRole.ADMIN), null, null));
    }

    @Test
    void canExecute_unknownAction_returnsFalse() {
        assertFalse(rbac.canExecute("nonexistent_action", auth(OperatorRole.ADMIN), null, null));
    }

    @Test
    void canExecute_forceTrue_nonSeniorRole_returnsFalse() {
        assertFalse(rbac.canExecute("channel_open", auth(OperatorRole.ENGINEER), "VIN001", true));
    }

    @Test
    void canExecute_forceTrue_seniorRole_returnsTrue() {
        assertTrue(rbac.canExecute("channel_open", auth(OperatorRole.SENIOR_ENGINEER), "VIN001", true));
    }

    @Test
    void canExecute_forceTrue_admin_returnsTrue() {
        assertTrue(rbac.canExecute("channel_open", auth(OperatorRole.ADMIN), "VIN001", true));
    }
}

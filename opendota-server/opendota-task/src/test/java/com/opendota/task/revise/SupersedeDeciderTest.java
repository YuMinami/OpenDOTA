package com.opendota.task.revise;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.task.entity.TaskDispatchRecord;
import com.opendota.task.revise.SupersedeDecider.SupersedeAction;
import com.opendota.task.revise.SupersedeDecider.SupersedeDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SupersedeDecider 单元测试。
 * 覆盖协议 §12.6.3 三分支决策 + 符合性用例 D-1, D-2, D-3。
 */
class SupersedeDeciderTest {

    private SupersedeDecider decider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String VIN = "LSVWA234567890123";

    @BeforeEach
    void setUp() {
        decider = new SupersedeDecider(objectMapper);
    }

    // ========== D-1: supersede queued 任务 ==========

    @Test
    void decide_queued_directReplace() {
        TaskDispatchRecord record = buildRecord("pending_online");
        SupersedeDecision decision = decider.decide(record, "{}");

        assertEquals(SupersedeAction.DIRECT_REPLACE, decision.action());
        assertNull(decision.rejectReason());
    }

    @Test
    void decide_pendingOnline_directReplace() {
        TaskDispatchRecord record = buildRecord("pending_online");
        SupersedeDecision decision = decider.decide(record, "{}");

        assertEquals(SupersedeAction.DIRECT_REPLACE, decision.action());
    }

    @Test
    void decide_failed_directReplace() {
        TaskDispatchRecord record = buildRecord("failed");
        SupersedeDecision decision = decider.decide(record, "{}");

        assertEquals(SupersedeAction.DIRECT_REPLACE, decision.action());
    }

    // ========== D-2: supersede executing routine_wait ==========

    @Test
    void decide_dispatched_noMacroType_markSuperseding() {
        TaskDispatchRecord record = buildRecord("dispatched");
        SupersedeDecision decision = decider.decide(record, "{\"steps\":[{\"serviceId\":1}]}");

        assertEquals(SupersedeAction.MARK_SUPERSEDING, decision.action());
        assertNull(decision.rejectReason());
    }

    @Test
    void decide_dispatched_routineWaitMacro_markSuperseding() {
        TaskDispatchRecord record = buildRecord("dispatched");
        String payload = "{\"macroType\":\"macro_routine_wait\"}";
        SupersedeDecision decision = decider.decide(record, payload);

        assertEquals(SupersedeAction.MARK_SUPERSEDING, decision.action());
    }

    @Test
    void decide_dispatched_rawUdsMacro_markSuperseding() {
        TaskDispatchRecord record = buildRecord("dispatched");
        String payload = "{\"steps\":[{\"macroType\":\"macro_routine_wait\"}]}";
        SupersedeDecision decision = decider.decide(record, payload);

        assertEquals(SupersedeAction.MARK_SUPERSEDING, decision.action());
    }

    // ========== D-3: supersede executing macro_data_transfer ==========

    @Test
    void decide_dispatched_dataTransferMacro_reject() {
        TaskDispatchRecord record = buildRecord("dispatched");
        String payload = "{\"macroType\":\"macro_data_transfer\"}";
        SupersedeDecision decision = decider.decide(record, payload);

        assertEquals(SupersedeAction.REJECT, decision.action());
        assertEquals("NON_INTERRUPTIBLE", decision.rejectReason());
    }

    @Test
    void decide_dispatched_securityMacro_reject() {
        TaskDispatchRecord record = buildRecord("dispatched");
        String payload = "{\"macroType\":\"macro_security\"}";
        SupersedeDecision decision = decider.decide(record, payload);

        assertEquals(SupersedeAction.REJECT, decision.action());
        assertEquals("NON_INTERRUPTIBLE", decision.rejectReason());
    }

    @Test
    void decide_dispatched_dataTransferInSteps_reject() {
        TaskDispatchRecord record = buildRecord("dispatched");
        String payload = "{\"steps\":[{\"macroType\":\"macro_data_transfer\"}]}";
        SupersedeDecision decision = decider.decide(record, payload);

        assertEquals(SupersedeAction.REJECT, decision.action());
    }

    // ========== 终态跳过 ==========

    @Test
    void decide_completed_skip() {
        TaskDispatchRecord record = buildRecord("completed");
        SupersedeDecision decision = decider.decide(record, "{}");

        assertEquals(SupersedeAction.SKIP, decision.action());
    }

    @Test
    void decide_canceled_skip() {
        TaskDispatchRecord record = buildRecord("canceled");
        SupersedeDecision decision = decider.decide(record, "{}");

        assertEquals(SupersedeAction.SKIP, decision.action());
    }

    @Test
    void decide_expired_skip() {
        TaskDispatchRecord record = buildRecord("expired");
        SupersedeDecision decision = decider.decide(record, "{}");

        assertEquals(SupersedeAction.SKIP, decision.action());
    }

    // ========== superseding 状态跳过 ==========

    @Test
    void decide_alreadySuperseding_skip() {
        TaskDispatchRecord record = buildRecord("superseding");
        SupersedeDecision decision = decider.decide(record, "{}");

        assertEquals(SupersedeAction.SKIP, decision.action());
    }

    // ========== 边界情况 ==========

    @Test
    void decide_nullPayload_noMacroType_markSuperseding() {
        TaskDispatchRecord record = buildRecord("dispatched");
        SupersedeDecision decision = decider.decide(record, null);

        assertEquals(SupersedeAction.MARK_SUPERSEDING, decision.action());
    }

    @Test
    void decide_emptyPayload_markSuperseding() {
        TaskDispatchRecord record = buildRecord("dispatched");
        SupersedeDecision decision = decider.decide(record, "");

        assertEquals(SupersedeAction.MARK_SUPERSEDING, decision.action());
    }

    @Test
    void decide_malformedPayload_markSuperseding() {
        TaskDispatchRecord record = buildRecord("dispatched");
        SupersedeDecision decision = decider.decide(record, "not-json");

        assertEquals(SupersedeAction.MARK_SUPERSEDING, decision.action());
    }

    private TaskDispatchRecord buildRecord(String status) {
        TaskDispatchRecord record = new TaskDispatchRecord();
        record.setTaskId("tsk_old_001");
        record.setTenantId("tenant-1");
        record.setVin(VIN);
        record.setDispatchStatus(status);
        return record;
    }
}

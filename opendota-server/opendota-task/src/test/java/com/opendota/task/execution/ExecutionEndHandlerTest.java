package com.opendota.task.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ExecutionEndHandler 单元测试。
 */
class ExecutionEndHandlerTest {

    private TaskExecutionLogRepository execLogRepo;
    private ExecutionMetrics metrics;
    private ExecutionEndHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String VIN = "LSVWA234567890123";
    private static final String TASK_ID = "task-cron-001";

    @BeforeEach
    void setUp() {
        execLogRepo = mock(TaskExecutionLogRepository.class);
        MeterRegistry registry = new SimpleMeterRegistry();
        metrics = new ExecutionMetrics(registry);
        handler = new ExecutionEndHandler(execLogRepo, metrics);
    }

    @Test
    void supports_executionEnd_returnsTrue() {
        assertTrue(handler.supports(DiagAction.EXECUTION_END));
    }

    @Test
    void supports_otherAction_returnsFalse() {
        assertFalse(handler.supports(DiagAction.EXECUTION_BEGIN));
    }

    @Test
    void handle_matchedEnd_updatesLog() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskId", TASK_ID);
        payload.put("executionSeq", 7);
        payload.put("beginMsgId", "msg-begin-001");
        payload.put("endAt", 1713300305200L);
        payload.put("overallStatus", 0);
        payload.put("executionDuration", 5200);

        DiagMessage<ObjectNode> envelope = new DiagMessage<>(
                "msg-end-001", System.currentTimeMillis(), VIN,
                DiagAction.EXECUTION_END, null, null, payload);

        when(execLogRepo.updateEnd(eq("msg-end-001"), eq(0), anyString(), eq(5200), eq(TASK_ID), eq(VIN), eq(7)))
                .thenReturn(1);

        handler.handle("dota/v1/event/execution/" + VIN, envelope);

        verify(execLogRepo).updateEnd(eq("msg-end-001"), eq(0), anyString(), eq(5200), eq(TASK_ID), eq(VIN), eq(7));
        assertEquals(1.0, metrics.endCounter().count());
    }

    @Test
    void handle_unmatchedEnd_logsWarning() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskId", TASK_ID);
        payload.put("executionSeq", 99); // begin 未到达
        payload.put("beginMsgId", "msg-begin-xxx");
        payload.put("overallStatus", 0);

        DiagMessage<ObjectNode> envelope = new DiagMessage<>(
                "msg-end-002", System.currentTimeMillis(), VIN,
                DiagAction.EXECUTION_END, null, null, payload);

        when(execLogRepo.updateEnd(anyString(), anyInt(), anyString(), any(), eq(TASK_ID), eq(VIN), eq(99)))
                .thenReturn(0);

        handler.handle("dota/v1/event/execution/" + VIN, envelope);

        // 不记录指标
        assertEquals(0.0, metrics.endCounter().count());
        assertEquals(0.0, metrics.endFailedCounter().count());
    }

    @Test
    void handle_failedExecution_recordsFailedMetric() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskId", TASK_ID);
        payload.put("executionSeq", 5);
        payload.put("overallStatus", 2); // failed
        payload.put("executionDuration", 10000);

        DiagMessage<ObjectNode> envelope = new DiagMessage<>(
                "msg-end-003", System.currentTimeMillis(), VIN,
                DiagAction.EXECUTION_END, null, null, payload);

        when(execLogRepo.updateEnd(anyString(), eq(2), anyString(), eq(10000), eq(TASK_ID), eq(VIN), eq(5)))
                .thenReturn(1);

        handler.handle("dota/v1/event/execution/" + VIN, envelope);

        assertEquals(1.0, metrics.endFailedCounter().count());
        assertEquals(0.0, metrics.endCounter().count());
    }

    @Test
    void handle_missingTaskId_ignores() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("executionSeq", 7);
        payload.put("overallStatus", 0);

        DiagMessage<ObjectNode> envelope = new DiagMessage<>(
                "msg-end-004", System.currentTimeMillis(), VIN,
                DiagAction.EXECUTION_END, null, null, payload);

        handler.handle("dota/v1/event/execution/" + VIN, envelope);

        verifyNoInteractions(execLogRepo);
    }

    @Test
    void handle_nonJsonPayload_ignores() {
        DiagMessage<String> envelope = new DiagMessage<>(
                "msg-end-005", System.currentTimeMillis(), VIN,
                DiagAction.EXECUTION_END, null, null, "not-json");

        handler.handle("dota/v1/event/execution/" + VIN, envelope);

        verifyNoInteractions(execLogRepo);
    }

    @Test
    void handle_duplicateEnd_ignoredBySqlWhere() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskId", TASK_ID);
        payload.put("executionSeq", 7);
        payload.put("overallStatus", 0);

        DiagMessage<ObjectNode> envelope = new DiagMessage<>(
                "msg-end-006", System.currentTimeMillis(), VIN,
                DiagAction.EXECUTION_END, null, null, payload);

        // end_reported_at IS NOT NULL → affected=0
        when(execLogRepo.updateEnd(anyString(), anyInt(), anyString(), any(), eq(TASK_ID), eq(VIN), eq(7)))
                .thenReturn(0);

        handler.handle("dota/v1/event/execution/" + VIN, envelope);

        // 不覆盖已有 end(幂等)
        assertEquals(0.0, metrics.endCounter().count());
    }
}

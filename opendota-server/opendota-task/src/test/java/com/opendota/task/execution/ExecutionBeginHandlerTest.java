package com.opendota.task.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ExecutionBeginHandler 单元测试。
 */
class ExecutionBeginHandlerTest {

    private TaskExecutionLogRepository execLogRepo;
    private JdbcTemplate jdbc;
    private ExecutionMetrics metrics;
    private ExecutionBeginHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String VIN = "LSVWA234567890123";
    private static final String TASK_ID = "task-cron-001";

    @BeforeEach
    void setUp() {
        execLogRepo = mock(TaskExecutionLogRepository.class);
        jdbc = mock(JdbcTemplate.class);
        MeterRegistry registry = new SimpleMeterRegistry();
        metrics = new ExecutionMetrics(registry);
        handler = new ExecutionBeginHandler(execLogRepo, jdbc, metrics);
    }

    @Test
    void supports_executionBegin_returnsTrue() {
        assertTrue(handler.supports(DiagAction.EXECUTION_BEGIN));
    }

    @Test
    void supports_otherAction_returnsFalse() {
        assertFalse(handler.supports(DiagAction.EXECUTION_END));
        assertFalse(handler.supports(DiagAction.SINGLE_RESP));
    }

    @Test
    void handle_newBegin_insertsAndUpdatesCount() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskId", TASK_ID);
        payload.put("executionSeq", 7);
        payload.put("triggerAt", 1713300300000L);
        payload.put("triggerSource", "cron");

        DiagMessage<ObjectNode> envelope = new DiagMessage<>(
                "msg-begin-001", System.currentTimeMillis(), VIN,
                DiagAction.EXECUTION_BEGIN, null, null, payload);

        when(execLogRepo.insertBegin(anyString(), eq(TASK_ID), eq(VIN), eq(7), eq(1713300300000L), eq("msg-begin-001")))
                .thenReturn(1);
        when(jdbc.update(anyString(), eq(7), eq(TASK_ID), eq(VIN))).thenReturn(1);

        handler.handle("dota/v1/event/execution/" + VIN, envelope);

        verify(execLogRepo).insertBegin(anyString(), eq(TASK_ID), eq(VIN), eq(7), eq(1713300300000L), eq("msg-begin-001"));
        verify(jdbc).update(contains("GREATEST"), eq(7), eq(TASK_ID), eq(VIN));
        assertEquals(1.0, metrics.beginCounter().count());
    }

    @Test
    void handle_duplicateBegin_skipsIdempotent() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskId", TASK_ID);
        payload.put("executionSeq", 7);
        payload.put("triggerAt", 1713300300000L);
        payload.put("triggerSource", "cron");

        DiagMessage<ObjectNode> envelope = new DiagMessage<>(
                "msg-begin-001", System.currentTimeMillis(), VIN,
                DiagAction.EXECUTION_BEGIN, null, null, payload);

        // ON CONFLICT DO NOTHING returns 0
        when(execLogRepo.insertBegin(anyString(), eq(TASK_ID), eq(VIN), eq(7), anyLong(), anyString()))
                .thenReturn(0);
        when(jdbc.update(anyString(), eq(7), eq(TASK_ID), eq(VIN))).thenReturn(1);

        handler.handle("dota/v1/event/execution/" + VIN, envelope);

        // 不记录指标(幂等跳过)
        assertEquals(0.0, metrics.beginCounter().count());
    }

    @Test
    void handle_missingTaskId_ignores() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("executionSeq", 7);
        payload.put("triggerAt", 1713300300000L);

        DiagMessage<ObjectNode> envelope = new DiagMessage<>(
                "msg-begin-002", System.currentTimeMillis(), VIN,
                DiagAction.EXECUTION_BEGIN, null, null, payload);

        handler.handle("dota/v1/event/execution/" + VIN, envelope);

        verifyNoInteractions(execLogRepo);
    }

    @Test
    void handle_missingExecutionSeq_ignores() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskId", TASK_ID);
        payload.put("triggerAt", 1713300300000L);

        DiagMessage<ObjectNode> envelope = new DiagMessage<>(
                "msg-begin-003", System.currentTimeMillis(), VIN,
                DiagAction.EXECUTION_BEGIN, null, null, payload);

        handler.handle("dota/v1/event/execution/" + VIN, envelope);

        verifyNoInteractions(execLogRepo);
    }

    @Test
    void handle_nonJsonPayload_ignores() {
        DiagMessage<String> envelope = new DiagMessage<>(
                "msg-begin-004", System.currentTimeMillis(), VIN,
                DiagAction.EXECUTION_BEGIN, null, null, "not-json");

        handler.handle("dota/v1/event/execution/" + VIN, envelope);

        verifyNoInteractions(execLogRepo);
    }

    @Test
    void handle_greatestPreventsCountRegression() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskId", TASK_ID);
        payload.put("executionSeq", 3); // 比当前 count 小
        payload.put("triggerAt", 1713300300000L);
        payload.put("triggerSource", "cron");

        DiagMessage<ObjectNode> envelope = new DiagMessage<>(
                "msg-begin-005", System.currentTimeMillis(), VIN,
                DiagAction.EXECUTION_BEGIN, null, null, payload);

        when(execLogRepo.insertBegin(anyString(), eq(TASK_ID), eq(VIN), eq(3), anyLong(), anyString()))
                .thenReturn(1);
        when(jdbc.update(contains("GREATEST"), eq(3), eq(TASK_ID), eq(VIN))).thenReturn(1);

        handler.handle("dota/v1/event/execution/" + VIN, envelope);

        // GREATEST(current_execution_count, 3) 确保不回退
        verify(jdbc).update(contains("GREATEST"), eq(3), eq(TASK_ID), eq(VIN));
    }
}

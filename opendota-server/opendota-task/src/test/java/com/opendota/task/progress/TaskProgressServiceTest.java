package com.opendota.task.progress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.web.BusinessException;
import com.opendota.task.entity.TaskDefinition;
import com.opendota.task.execution.TaskExecutionLogRepository;
import com.opendota.task.execution.TaskExecutionLogRepository.ExecutionLogRow;
import com.opendota.task.progress.TaskBoardProgressRepository.BoardProgressRow;
import com.opendota.task.progress.TaskProgressService.TaskExecutionLogItem;
import com.opendota.task.progress.TaskProgressService.TaskProgressView;
import com.opendota.task.repository.TaskDispatchRecordRepository;
import com.opendota.task.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskProgressService 单元测试(REST §6.4 / §6.5,Phase 4 Step 4.8)。
 */
class TaskProgressServiceTest {

    private static final String TENANT = "chery-hq";
    private static final String TASK_ID = "tsk_abc123";

    private TaskService taskService;
    private TaskDispatchRecordRepository dispatchRepo;
    private TaskBoardProgressRepository boardRepo;
    private TaskExecutionLogRepository executionLogRepo;
    private TaskProgressService service;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        dispatchRepo = mock(TaskDispatchRecordRepository.class);
        boardRepo = mock(TaskBoardProgressRepository.class);
        executionLogRepo = mock(TaskExecutionLogRepository.class);
        service = new TaskProgressService(
                taskService, dispatchRepo, boardRepo, executionLogRepo, new ObjectMapper());
    }

    // ========== getProgress ==========

    @Test
    void getProgress_returnsAll11StatusFieldsEvenWhenZero() {
        // 任务存在,但只有 2 个状态有计数,其余 9 个状态应回填 0
        TaskDefinition def = buildTaskDefinition();
        when(taskService.getTask(TASK_ID, TENANT)).thenReturn(def);
        when(dispatchRepo.countByStatusGrouped(TASK_ID)).thenReturn(List.of(
                new Object[]{"completed", 2700L},
                new Object[]{"executing", 150L}
        ));
        when(boardRepo.findByTaskId(TASK_ID)).thenReturn(Optional.of(
                new BoardProgressRow(TASK_ID, 2700, 50, 40, 10, 5000, "in_progress")));

        TaskProgressView view = service.getProgress(TASK_ID, TENANT);

        // 11 个字段全部存在
        assertEquals(11, view.progress().size());
        // 协议 §14.4.3 字段顺序
        List<String> keys = List.copyOf(view.progress().keySet());
        assertEquals("pending_online", keys.get(0));
        assertEquals("expired", keys.get(10));

        // 已上报的覆盖
        assertEquals(2700, view.progress().get("completed"));
        assertEquals(150, view.progress().get("executing"));
        // 未上报的为 0
        assertEquals(0, view.progress().get("pending_online"));
        assertEquals(0, view.progress().get("paused"));

        // 看板字段
        assertEquals(5000, view.totalTargets());
        assertEquals("in_progress", view.boardStatus());
        // ADR D1: completionRate = completed/total = 2700/5000 = 0.54
        assertEquals(0.54, view.completionRate(), 1e-9);

        assertEquals(TASK_ID, view.taskId());
        assertEquals("DTC 扫描", view.taskName());
    }

    @Test
    void getProgress_emptyTaskFallsBackToInProgressWithZeroTotal() {
        // 刚创建的任务还没有 dispatch_record,视图无行
        when(taskService.getTask(TASK_ID, TENANT)).thenReturn(buildTaskDefinition());
        when(dispatchRepo.countByStatusGrouped(TASK_ID)).thenReturn(List.of());
        when(boardRepo.findByTaskId(TASK_ID)).thenReturn(Optional.empty());

        TaskProgressView view = service.getProgress(TASK_ID, TENANT);

        assertEquals(0, view.totalTargets());
        assertEquals("in_progress", view.boardStatus());
        assertEquals(0.0, view.completionRate(), 1e-9);
        assertEquals(11, view.progress().size());
        view.progress().values().forEach(v -> assertEquals(0, v));
    }

    @Test
    void getProgress_completionRateUsesCompletedOnly() {
        // ADR D1 验证: failed/canceled/expired 不计入 completionRate
        when(taskService.getTask(TASK_ID, TENANT)).thenReturn(buildTaskDefinition());
        when(dispatchRepo.countByStatusGrouped(TASK_ID)).thenReturn(List.of(
                new Object[]{"completed", 50L},
                new Object[]{"failed", 30L},
                new Object[]{"canceled", 10L},
                new Object[]{"expired", 10L}
        ));
        when(boardRepo.findByTaskId(TASK_ID)).thenReturn(Optional.of(
                new BoardProgressRow(TASK_ID, 50, 30, 10, 10, 100, "partial_completed")));

        TaskProgressView view = service.getProgress(TASK_ID, TENANT);

        // 50/100 = 0.5,而非 (50+30+10+10)/100 = 1.0
        assertEquals(0.5, view.completionRate(), 1e-9);
        assertEquals("partial_completed", view.boardStatus());
    }

    @Test
    void getProgress_unknownStatusValueIsIgnoredNotThrowing() {
        // 历史脏数据兜底: 出现未知状态值不应导致 500
        when(taskService.getTask(TASK_ID, TENANT)).thenReturn(buildTaskDefinition());
        when(dispatchRepo.countByStatusGrouped(TASK_ID)).thenReturn(List.of(
                new Object[]{"completed", 5L},
                new Object[]{"weird_legacy_value", 999L}  // 未知值
        ));
        when(boardRepo.findByTaskId(TASK_ID)).thenReturn(Optional.of(
                new BoardProgressRow(TASK_ID, 5, 0, 0, 0, 5, "all_completed")));

        TaskProgressView view = service.getProgress(TASK_ID, TENANT);

        assertEquals(5, view.progress().get("completed"));
        // 未知值不应混进任何标准字段
        assertEquals(11, view.progress().size());
    }

    @Test
    void getProgress_propagatesNotFoundFromTaskService() {
        // 跨租户访问由 TaskService.getTask 抛 BusinessException(E40401)
        when(taskService.getTask(TASK_ID, TENANT))
                .thenThrow(new BusinessException(com.opendota.common.web.ApiError.E40401, "任务不存在"));

        assertThrows(BusinessException.class, () -> service.getProgress(TASK_ID, TENANT));
    }

    // ========== listExecutions ==========

    @Test
    void listExecutions_convertsTimestampsToEpochMs() {
        when(taskService.getTask(TASK_ID, TENANT)).thenReturn(buildTaskDefinition());
        Timestamp triggerTs = Timestamp.valueOf(LocalDateTime.of(2026, 4, 30, 12, 0, 0));
        Timestamp beginTs = Timestamp.valueOf(LocalDateTime.of(2026, 4, 30, 12, 0, 1));
        Timestamp endTs = Timestamp.valueOf(LocalDateTime.of(2026, 4, 30, 12, 0, 6));
        ExecutionLogRow row = new ExecutionLogRow(
                7, "LSVWA234567890123",
                triggerTs, 0, 5200,
                beginTs, endTs,
                null, "{\"dtcs\":[\"P0420\"]}");
        when(executionLogRepo.findByTaskId(eq(TENANT), eq(TASK_ID), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), Pageable.ofSize(50), 1L));

        Page<TaskExecutionLogItem> result = service.listExecutions(TASK_ID, TENANT, null, 1, 50);

        assertEquals(1L, result.getTotalElements());
        TaskExecutionLogItem item = result.getContent().get(0);
        assertEquals(7, item.executionSeq());
        assertEquals("LSVWA234567890123", item.vin());
        assertEquals(0, item.overallStatus());
        assertEquals(5200, item.executionDuration());
        assertNotNull(item.triggerTime());
        assertNotNull(item.beginReportedAt());
        assertNotNull(item.endReportedAt());
        assertNull(item.missCompensation());
        // resultPayload JSON 应被解析成 Map
        assertNotNull(item.resultPayload());
    }

    @Test
    void listExecutions_caps0PageSizeAndNegativePage() {
        // size 上限 200,page 最小 1,内部转 0-based
        when(taskService.getTask(TASK_ID, TENANT)).thenReturn(buildTaskDefinition());
        when(executionLogRepo.findByTaskId(eq(TENANT), eq(TASK_ID), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(200), 0L));

        // 输入 page=0,size=999
        Page<TaskExecutionLogItem> result = service.listExecutions(TASK_ID, TENANT, null, 0, 999);

        // 不抛异常,且 service 用 size=200 调用了 repo
        assertNotNull(result);
        verify(executionLogRepo).findByTaskId(eq(TENANT), eq(TASK_ID), any(),
                org.mockito.ArgumentMatchers.argThat(p ->
                        p != null && p.getPageSize() == 200 && p.getPageNumber() == 0));
    }

    @Test
    void listExecutions_passesVinFilterThrough() {
        when(taskService.getTask(TASK_ID, TENANT)).thenReturn(buildTaskDefinition());
        when(executionLogRepo.findByTaskId(eq(TENANT), eq(TASK_ID), eq("LSVWA234567890123"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0L));

        service.listExecutions(TASK_ID, TENANT, "LSVWA234567890123", 1, 50);

        verify(executionLogRepo).findByTaskId(eq(TENANT), eq(TASK_ID), eq("LSVWA234567890123"), any(Pageable.class));
    }

    @Test
    void listExecutions_propagatesNotFound() {
        when(taskService.getTask(TASK_ID, TENANT))
                .thenThrow(new BusinessException(com.opendota.common.web.ApiError.E40401, "任务不存在"));

        assertThrows(BusinessException.class,
                () -> service.listExecutions(TASK_ID, TENANT, null, 1, 50));
    }

    // ========== fixture ==========

    private TaskDefinition buildTaskDefinition() {
        TaskDefinition def = new TaskDefinition();
        def.setTaskId(TASK_ID);
        def.setTenantId(TENANT);
        def.setTaskName("DTC 扫描");
        def.setVersion(2);
        def.setSupersedesTaskId("tsk_abc000");
        def.setStatus("active");
        return def;
    }
}

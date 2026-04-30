package com.opendota.task.revise;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.web.BusinessException;
import com.opendota.task.entity.TaskDefinition;
import com.opendota.task.entity.TaskDispatchRecord;
import com.opendota.task.entity.TaskTarget;
import com.opendota.task.outbox.OutboxService;
import com.opendota.task.repository.TaskDefinitionRepository;
import com.opendota.task.repository.TaskDispatchRecordRepository;
import com.opendota.task.repository.TaskTargetRepository;
import com.opendota.task.revise.TaskReviseService.ReviseTaskRequest;
import com.opendota.task.revise.TaskReviseService.ReviseTaskResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TaskReviseService 单元测试。
 * 覆盖协议 §12.6.3 符合性用例 D-1, D-2, D-3。
 */
class TaskReviseServiceTest {

    private TaskDefinitionRepository taskDefRepo;
    private TaskTargetRepository taskTargetRepo;
    private TaskDispatchRecordRepository dispatchRecordRepo;
    private SupersedeDecider supersedeDecider;
    private OutboxService outboxService;
    private ObjectMapper objectMapper;
    private TaskReviseService service;

    private static final String TENANT_ID = "tenant-1";
    private static final String OPERATOR_ID = "op-001";
    private static final String OLD_TASK_ID = "tsk_old_001";
    private static final String VIN_1 = "LSVWA234567890123";
    private static final String VIN_2 = "LSVWB234567890456";

    @BeforeEach
    void setUp() {
        taskDefRepo = mock(TaskDefinitionRepository.class);
        taskTargetRepo = mock(TaskTargetRepository.class);
        dispatchRecordRepo = mock(TaskDispatchRecordRepository.class);
        objectMapper = new ObjectMapper();
        supersedeDecider = new SupersedeDecider(objectMapper);
        outboxService = mock(OutboxService.class);
        service = new TaskReviseService(
                taskDefRepo, taskTargetRepo, dispatchRecordRepo,
                supersedeDecider, outboxService, objectMapper);
    }

    // ========== D-1: 修订 queued 任务 ==========

    @Test
    void revise_queuedTask_directReplace() {
        TaskDefinition oldDef = buildOldTask("active", "{\"ecuScope\":[\"BMS\"]}");
        TaskDispatchRecord record1 = buildDispatchRecord(VIN_1, "pending_online");
        TaskDispatchRecord record2 = buildDispatchRecord(VIN_2, "pending_online");

        when(taskDefRepo.findByTaskId(OLD_TASK_ID)).thenReturn(Optional.of(oldDef));
        when(taskTargetRepo.findByTaskDefinition_TaskId(OLD_TASK_ID)).thenReturn(List.of());
        when(dispatchRecordRepo.findActiveByTaskId(OLD_TASK_ID)).thenReturn(List.of(record1, record2));
        when(dispatchRecordRepo.save(any(TaskDispatchRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        when(taskDefRepo.save(any(TaskDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviseTaskRequest req = new ReviseTaskRequest(null, 3, null, null);
        ReviseTaskResponse resp = service.reviseTask(OLD_TASK_ID, req, OPERATOR_ID, TENANT_ID);

        // 新任务已创建
        assertNotNull(resp.taskId());
        assertNotEquals(OLD_TASK_ID, resp.taskId());
        assertEquals(2, resp.version());
        assertEquals(OLD_TASK_ID, resp.supersedesTaskId());
        assertEquals(2, resp.dispatchedCount());
        assertEquals(0, resp.rejectedCount());
        assertTrue(resp.rejectedVins().isEmpty());

        // 旧任务已取消
        assertEquals("canceled", oldDef.getStatus());

        // 两条旧 dispatch_record 都被取消
        assertEquals("canceled", record1.getDispatchStatus());
        assertEquals(resp.taskId(), record1.getSupersededBy());
        assertEquals("canceled", record2.getDispatchStatus());
        assertEquals(resp.taskId(), record2.getSupersededBy());

        // outbox 写了 2 条(每条 VIN 一条)
        verify(outboxService, times(2)).enqueueDispatchCommand(eq(TENANT_ID), eq(resp.taskId()), any());
    }

    // ========== D-2: 修订 executing routine_wait ==========

    @Test
    void revise_executingRoutineWait_markSuperseding() {
        TaskDefinition oldDef = buildOldTask("active", "{\"ecuScope\":[\"BMS\"],\"macroType\":\"macro_routine_wait\"}");
        TaskDispatchRecord record = buildDispatchRecord(VIN_1, "dispatched");

        when(taskDefRepo.findByTaskId(OLD_TASK_ID)).thenReturn(Optional.of(oldDef));
        when(taskTargetRepo.findByTaskDefinition_TaskId(OLD_TASK_ID)).thenReturn(List.of());
        when(dispatchRecordRepo.findActiveByTaskId(OLD_TASK_ID)).thenReturn(List.of(record));
        when(dispatchRecordRepo.save(any(TaskDispatchRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        when(taskDefRepo.save(any(TaskDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviseTaskRequest req = new ReviseTaskRequest(null, null, null, null);
        ReviseTaskResponse resp = service.reviseTask(OLD_TASK_ID, req, OPERATOR_ID, TENANT_ID);

        assertEquals(1, resp.dispatchedCount());
        assertEquals(0, resp.rejectedCount());

        // 旧记录标记为 superseding
        assertEquals("superseding", record.getDispatchStatus());
        assertEquals(resp.taskId(), record.getSupersededBy());
    }

    // ========== D-3: 修订 executing macro_data_transfer ==========

    @Test
    void revise_executingDataTransfer_reject() {
        TaskDefinition oldDef = buildOldTask("active", "{\"ecuScope\":[\"BMS\"],\"macroType\":\"macro_data_transfer\"}");
        TaskDispatchRecord record = buildDispatchRecord(VIN_1, "dispatched");

        when(taskDefRepo.findByTaskId(OLD_TASK_ID)).thenReturn(Optional.of(oldDef));
        when(taskTargetRepo.findByTaskDefinition_TaskId(OLD_TASK_ID)).thenReturn(List.of());
        when(dispatchRecordRepo.findActiveByTaskId(OLD_TASK_ID)).thenReturn(List.of(record));
        when(dispatchRecordRepo.save(any(TaskDispatchRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        when(taskDefRepo.save(any(TaskDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviseTaskRequest req = new ReviseTaskRequest(null, null, null, null);
        ReviseTaskResponse resp = service.reviseTask(OLD_TASK_ID, req, OPERATOR_ID, TENANT_ID);

        assertEquals(0, resp.dispatchedCount());
        assertEquals(1, resp.rejectedCount());
        assertEquals(List.of(VIN_1), resp.rejectedVins());

        // 旧记录的 supersededBy 已设置(指向新任务),但状态不变
        assertEquals("dispatched", record.getDispatchStatus());
        assertEquals(resp.taskId(), record.getSupersededBy());
    }

    // ========== 混合场景 ==========

    @Test
    void revise_mixedRecords_handlesAllBranches() {
        TaskDefinition oldDef = buildOldTask("active", "{\"ecuScope\":[\"BMS\"]}");
        TaskDispatchRecord queued = buildDispatchRecord(VIN_1, "pending_online");
        TaskDispatchRecord executing = buildDispatchRecord(VIN_2, "dispatched");

        when(taskDefRepo.findByTaskId(OLD_TASK_ID)).thenReturn(Optional.of(oldDef));
        when(taskTargetRepo.findByTaskDefinition_TaskId(OLD_TASK_ID)).thenReturn(List.of());
        when(dispatchRecordRepo.findActiveByTaskId(OLD_TASK_ID)).thenReturn(List.of(queued, executing));
        when(dispatchRecordRepo.save(any(TaskDispatchRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        when(taskDefRepo.save(any(TaskDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviseTaskRequest req = new ReviseTaskRequest(null, null, null, null);
        ReviseTaskResponse resp = service.reviseTask(OLD_TASK_ID, req, OPERATOR_ID, TENANT_ID);

        // VIN_1 queued → direct replace (dispatched), VIN_2 dispatched → superseding (dispatched)
        assertEquals(2, resp.dispatchedCount());
        assertEquals(0, resp.rejectedCount());

        // queued → canceled
        assertEquals("canceled", queued.getDispatchStatus());
        // dispatched → superseding
        assertEquals("superseding", executing.getDispatchStatus());
    }

    // ========== 校验失败 ==========

    @Test
    void revise_oldTaskNotFound_throws() {
        when(taskDefRepo.findByTaskId(OLD_TASK_ID)).thenReturn(Optional.empty());

        ReviseTaskRequest req = new ReviseTaskRequest(null, null, null, null);
        assertThrows(BusinessException.class,
                () -> service.reviseTask(OLD_TASK_ID, req, OPERATOR_ID, TENANT_ID));
    }

    @Test
    void revise_oldTaskDifferentTenant_throws() {
        TaskDefinition oldDef = buildOldTask("active", "{}");
        oldDef.setTenantId("other-tenant");
        when(taskDefRepo.findByTaskId(OLD_TASK_ID)).thenReturn(Optional.of(oldDef));

        ReviseTaskRequest req = new ReviseTaskRequest(null, null, null, null);
        assertThrows(BusinessException.class,
                () -> service.reviseTask(OLD_TASK_ID, req, OPERATOR_ID, TENANT_ID));
    }

    @Test
    void revise_oldTaskCompleted_throws() {
        TaskDefinition oldDef = buildOldTask("completed", "{}");
        when(taskDefRepo.findByTaskId(OLD_TASK_ID)).thenReturn(Optional.of(oldDef));

        ReviseTaskRequest req = new ReviseTaskRequest(null, null, null, null);
        assertThrows(BusinessException.class,
                () -> service.reviseTask(OLD_TASK_ID, req, OPERATOR_ID, TENANT_ID));
    }

    @Test
    void revise_oldTaskCanceled_throws() {
        TaskDefinition oldDef = buildOldTask("canceled", "{}");
        when(taskDefRepo.findByTaskId(OLD_TASK_ID)).thenReturn(Optional.of(oldDef));

        ReviseTaskRequest req = new ReviseTaskRequest(null, null, null, null);
        assertThrows(BusinessException.class,
                () -> service.reviseTask(OLD_TASK_ID, req, OPERATOR_ID, TENANT_ID));
    }

    // ========== 版本链 ==========

    @Test
    void revise_versionIncrementedAndChainLinked() {
        TaskDefinition oldDef = buildOldTask("active", "{\"ecuScope\":[\"BMS\"]}");
        oldDef.setVersion(3);

        when(taskDefRepo.findByTaskId(OLD_TASK_ID)).thenReturn(Optional.of(oldDef));
        when(taskTargetRepo.findByTaskDefinition_TaskId(OLD_TASK_ID)).thenReturn(List.of());
        when(dispatchRecordRepo.findActiveByTaskId(OLD_TASK_ID)).thenReturn(List.of());
        when(taskDefRepo.save(any(TaskDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviseTaskRequest req = new ReviseTaskRequest(null, null, null, null);
        ReviseTaskResponse resp = service.reviseTask(OLD_TASK_ID, req, OPERATOR_ID, TENANT_ID);

        assertEquals(4, resp.version());
        assertEquals(OLD_TASK_ID, resp.supersedesTaskId());
    }

    // ========== 请求字段覆盖 ==========

    @Test
    void revise_requestOverridesFields() {
        TaskDefinition oldDef = buildOldTask("active", "{\"ecuScope\":[\"BMS\"]}");
        oldDef.setTaskName("旧名称");
        oldDef.setPriority(5);

        when(taskDefRepo.findByTaskId(OLD_TASK_ID)).thenReturn(Optional.of(oldDef));
        when(taskTargetRepo.findByTaskDefinition_TaskId(OLD_TASK_ID)).thenReturn(List.of());
        when(dispatchRecordRepo.findActiveByTaskId(OLD_TASK_ID)).thenReturn(List.of());
        ArgumentCaptor<TaskDefinition> captor = ArgumentCaptor.forClass(TaskDefinition.class);
        when(taskDefRepo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        ReviseTaskRequest req = new ReviseTaskRequest("新名称", 2, null, null);
        service.reviseTask(OLD_TASK_ID, req, OPERATOR_ID, TENANT_ID);

        // save 被调用两次:第一次是新定义,第二次是旧定义(canceled)
        List<TaskDefinition> allSaved = captor.getAllValues();
        TaskDefinition savedNew = allSaved.get(0);
        assertEquals("新名称", savedNew.getTaskName());
        assertEquals(2, savedNew.getPriority());
    }

    // ========== 辅助方法 ==========

    private TaskDefinition buildOldTask(String status, String diagPayload) {
        TaskDefinition def = new TaskDefinition();
        def.setTaskId(OLD_TASK_ID);
        def.setTenantId(TENANT_ID);
        def.setTaskName("旧任务");
        def.setVersion(1);
        def.setPriority(5);
        def.setScheduleType("periodic");
        def.setScheduleConfig("{\"cronExpression\":\"0 */5 * * * ?\"}");
        def.setMissPolicy("skip_all");
        def.setPayloadType("batch");
        def.setDiagPayload(diagPayload);
        def.setPayloadHash("abc123");
        def.setStatus(status);
        def.setCreatedBy(OPERATOR_ID);
        return def;
    }

    private TaskDispatchRecord buildDispatchRecord(String vin, String status) {
        TaskDispatchRecord record = new TaskDispatchRecord();
        record.setTaskId(OLD_TASK_ID);
        record.setTenantId(TENANT_ID);
        record.setVin(vin);
        record.setEcuScope("[\"BMS\"]");
        record.setDispatchStatus(status);
        return record;
    }
}

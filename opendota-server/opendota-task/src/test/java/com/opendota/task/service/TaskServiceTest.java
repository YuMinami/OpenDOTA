package com.opendota.task.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opendota.common.web.ApiError;
import com.opendota.common.web.BusinessException;
import com.opendota.task.controller.TaskController;
import com.opendota.task.entity.TaskDefinition;
import com.opendota.task.entity.TaskDispatchRecord;
import com.opendota.task.outbox.OutboxService;
import com.opendota.task.repository.TaskDefinitionRepository;
import com.opendota.task.repository.TaskDispatchRecordRepository;
import com.opendota.task.repository.TaskTargetRepository;
import com.opendota.task.scope.TargetScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TaskService 单元测试 — 覆盖 Phase 4 Step 4.1 验收项:
 * <ul>
 *   <li>创建 100 辆车的 batch 任务,{@code totalTargets=100} 且 dispatch_record 生成 100 行</li>
 *   <li>违反 R3({@code maxExecutions=-1} 无 {@code executeValidUntil})返回 41001</li>
 *   <li>覆盖 E41002 / E41004 / E41005 / E41010 / E41011 / E41012 边界,确保 R1-R3 完整生效</li>
 * </ul>
 */
class TaskServiceTest {

    private TaskDefinitionRepository taskDefRepo;
    private TaskTargetRepository taskTargetRepo;
    private TaskDispatchRecordRepository dispatchRecordRepo;
    private ObjectMapper objectMapper;
    private OutboxService outboxService;
    private TargetScopeResolver scopeResolver;
    private TaskService service;

    @BeforeEach
    void setUp() {
        taskDefRepo = mock(TaskDefinitionRepository.class);
        taskTargetRepo = mock(TaskTargetRepository.class);
        dispatchRecordRepo = mock(TaskDispatchRecordRepository.class);
        objectMapper = new ObjectMapper();
        outboxService = mock(OutboxService.class);
        scopeResolver = mock(TargetScopeResolver.class);
        service = new TaskService(taskDefRepo, taskTargetRepo, dispatchRecordRepo,
                objectMapper, outboxService, scopeResolver);
    }

    // ========== Step 4.1 验收 1: 100 辆车 batch ==========

    @Test
    @SuppressWarnings("unchecked")
    void createTask_100VehicleBatch_persists100DispatchRecords() {
        // 构造 100 个 VIN
        List<String> vins = IntStream.range(0, 100)
                .mapToObj(i -> String.format("LSVWA%012d", i))
                .toList();
        when(scopeResolver.resolve(eq("vin_list"), anyString(), eq("tenant-A")))
                .thenReturn(vins);

        TaskController.CreateTaskRequest req = batchOnceRequest(vins);

        TaskController.CreateTaskResponse resp = service.createTask(req, "operator-x", "tenant-A");

        // 验收点 1: 响应 totalTargets = 100
        assertEquals(100, resp.totalTargets(), "totalTargets 必须为 100");
        assertNotNull(resp.taskId(), "taskId 应被生成");
        assertTrue(resp.taskId().startsWith("tsk_"), "taskId 形如 tsk_xxxx");

        // 验收点 2: dispatch_record 批量保存了 100 行
        ArgumentCaptor<List<TaskDispatchRecord>> recordsCaptor = ArgumentCaptor.forClass(List.class);
        verify(dispatchRecordRepo).saveAll(recordsCaptor.capture());
        assertEquals(100, recordsCaptor.getValue().size(), "dispatch_record 必须正好生成 100 行");

        // 每条记录的 VIN 唯一且来自目标列表
        List<String> recordedVins = recordsCaptor.getValue().stream()
                .map(TaskDispatchRecord::getVin)
                .toList();
        assertEquals(100, recordedVins.stream().distinct().count(), "VIN 不应有重复");
        assertTrue(recordedVins.containsAll(vins), "所有目标 VIN 都应有 dispatch_record");

        // 同事务内写 outbox: 每条 VIN 一条 outbox event
        verify(outboxService, times(100)).enqueueDispatchCommand(eq("tenant-A"), anyString(), any());
    }

    // ========== Step 4.1 验收 2: R1 违例(maxExecutions=-1 无 executeValidUntil)→ 41001 ==========

    @Test
    void createTask_maxExecutionsMinusOneWithoutExecuteValidUntil_throwsE41001() {
        ObjectNode scheduleCfg = objectMapper.createObjectNode();
        scheduleCfg.put("cronExpression", "0 */5 * * * ?");
        scheduleCfg.put("maxExecutions", -1);
        // 故意不传 executeValidUntil

        TaskController.CreateTaskRequest req = new TaskController.CreateTaskRequest(
                "永动机任务", 5, null, null, null, null,
                "periodic", scheduleCfg, null, "batch",
                Map.of("ecuScope", List.of("VCU"), "ecuName", "VCU", "steps", List.of()),
                null,
                new TaskController.TargetScope("vin_list", "snapshot",
                        Map.of("vins", List.of("LSVWA234567890123"))),
                false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTask(req, "operator-x", "tenant-A"));
        assertEquals(ApiError.E41001.code(), ex.code(),
                "R1 违例必须返回 41001 错误码");
        assertTrue(ex.getMessage().contains("永动机") || ex.getMessage().contains("maxExecutions"),
                "异常消息应说明防永动机规则");
        // 不应进入持久化路径
        verifyNoInteractions(taskDefRepo, dispatchRecordRepo, outboxService);
    }

    // ========== R2 (E41002): ecuScope 缺失 ==========

    @Test
    void createTask_missingEcuScope_throwsE41002() {
        TaskController.CreateTaskRequest req = new TaskController.CreateTaskRequest(
                "无 ECU 任务", 5, null, null, null, null,
                "once", Map.of(), null, "batch",
                Map.of("steps", List.of()), // 没有 ecuScope 字段
                null,
                new TaskController.TargetScope("vin_list", "snapshot",
                        Map.of("vins", List.of("LSVWA234567890123"))),
                false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTask(req, "operator-x", "tenant-A"));
        assertEquals(ApiError.E41002.code(), ex.code());
    }

    // ========== E41004: periodic 任务无 cron 也无 intervalMs ==========

    @Test
    void createTask_periodicWithoutCronOrInterval_throwsE41004() {
        TaskController.CreateTaskRequest req = new TaskController.CreateTaskRequest(
                "无 cron 周期任务", 5, null, null, null, 9_000_000_000_000L,
                "periodic", Map.of(), // 空 schedule config
                null, "batch",
                Map.of("ecuScope", List.of("VCU"), "ecuName", "VCU", "steps", List.of()),
                null,
                new TaskController.TargetScope("vin_list", "snapshot",
                        Map.of("vins", List.of("LSVWA234567890123"))),
                false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTask(req, "operator-x", "tenant-A"));
        assertEquals(ApiError.E41004.code(), ex.code());
    }

    // ========== E41005: payloadHash 不匹配 ==========

    @Test
    void createTask_payloadHashMismatch_throwsE41005() {
        when(scopeResolver.resolve(anyString(), anyString(), anyString()))
                .thenReturn(List.of("LSVWA234567890123"));

        TaskController.CreateTaskRequest req = new TaskController.CreateTaskRequest(
                "hash 不匹配任务", 5, null, null, null, null,
                "once", Map.of(),
                null, "batch",
                Map.of("ecuScope", List.of("VCU"), "ecuName", "VCU", "steps", List.of()),
                "deadbeef".repeat(8), // 故意错的 hash
                new TaskController.TargetScope("vin_list", "snapshot",
                        Map.of("vins", List.of("LSVWA234567890123"))),
                false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTask(req, "operator-x", "tenant-A"));
        assertEquals(ApiError.E41005.code(), ex.code());
    }

    // ========== E41010: executeValidUntil < validUntil ==========

    @Test
    void createTask_executeValidUntilBeforeValidUntil_throwsE41010() {
        TaskController.CreateTaskRequest req = new TaskController.CreateTaskRequest(
                "时间窗倒挂", 5, null,
                9_000_000_000_000L, // validUntil 远未来
                null,
                1_000_000_000_000L, // executeValidUntil < validUntil
                "once", Map.of(), null, "batch",
                Map.of("ecuScope", List.of("VCU"), "ecuName", "VCU", "steps", List.of()),
                null,
                new TaskController.TargetScope("vin_list", "snapshot",
                        Map.of("vins", List.of("LSVWA234567890123"))),
                false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTask(req, "operator-x", "tenant-A"));
        assertEquals(ApiError.E41010.code(), ex.code());
    }

    // ========== E41012: dynamic + 非 periodic/conditional 组合 ==========

    @Test
    void createTask_dynamicScopeWithOnce_throwsE41012() {
        when(scopeResolver.resolve(anyString(), anyString(), anyString()))
                .thenReturn(List.of("LSVWA234567890123"));

        TaskController.CreateTaskRequest req = new TaskController.CreateTaskRequest(
                "dynamic+once 非法", 5, null, null, null, null,
                "once", Map.of(), null, "batch",
                Map.of("ecuScope", List.of("VCU"), "ecuName", "VCU", "steps", List.of()),
                null,
                new TaskController.TargetScope("model", "dynamic",
                        Map.of("modelId", "model-A")),
                false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTask(req, "operator-x", "tenant-A"));
        assertEquals(ApiError.E41012.code(), ex.code());
    }

    // ========== E41007: targetScope 解析为 0 车 ==========

    @Test
    void createTask_targetScopeResolvesToEmpty_throwsE41007() {
        when(scopeResolver.resolve(anyString(), anyString(), anyString()))
                .thenReturn(List.of()); // 0 车

        TaskController.CreateTaskRequest req = new TaskController.CreateTaskRequest(
                "空目标任务", 5, null, null, null, null,
                "once", Map.of(), null, "batch",
                Map.of("ecuScope", List.of("VCU"), "ecuName", "VCU", "steps", List.of()),
                null,
                new TaskController.TargetScope("vin_list", "snapshot",
                        Map.of("vins", List.of())),
                false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTask(req, "operator-x", "tenant-A"));
        assertEquals(ApiError.E41007.code(), ex.code());
    }

    // ========== getTask: 跨租户访问 → 404(E40401)防越权 ==========

    @Test
    void getTask_crossTenantAccess_throws404() {
        TaskDefinition def = new TaskDefinition();
        def.setTaskId("tsk_other");
        def.setTenantId("tenant-OTHER");
        when(taskDefRepo.findByTaskId("tsk_other"))
                .thenReturn(java.util.Optional.of(def));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getTask("tsk_other", "tenant-A"));
        assertEquals(ApiError.E40401.code(), ex.code());
    }

    // ========== 辅助:构造合法的 batch+once 请求 ==========

    private TaskController.CreateTaskRequest batchOnceRequest(List<String> vins) {
        return new TaskController.CreateTaskRequest(
                "批量 100 车任务", 5, null, null, null, null,
                "once",
                Map.of(),
                null,
                "batch",
                Map.of("ecuScope", List.of("VCU"),
                        "ecuName", "VCU",
                        "steps", List.of(
                                Map.of("type", "raw_uds", "reqData", "22F190", "timeoutMs", 3000))),
                null,
                new TaskController.TargetScope("vin_list", "snapshot",
                        Map.of("vins", new ArrayList<>(vins))),
                false);
    }
}

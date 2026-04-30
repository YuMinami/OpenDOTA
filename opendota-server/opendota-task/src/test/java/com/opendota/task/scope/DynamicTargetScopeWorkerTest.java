package com.opendota.task.scope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.task.entity.TaskDefinition;
import com.opendota.task.entity.TaskDispatchRecord;
import com.opendota.task.entity.TaskTarget;
import com.opendota.task.outbox.OutboxService;
import com.opendota.task.repository.TaskDefinitionRepository;
import com.opendota.task.repository.TaskDispatchRecordRepository;
import com.opendota.task.repository.TaskTargetRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DynamicTargetScopeWorker 单元测试。
 */
class DynamicTargetScopeWorkerTest {

    private JdbcTemplate jdbcTemplate;
    private TaskTargetRepository taskTargetRepo;
    private TaskDefinitionRepository taskDefRepo;
    private TaskDispatchRecordRepository dispatchRecordRepo;
    private TargetScopeResolver scopeResolver;
    private OutboxService outboxService;
    private ScopeMetrics metrics;
    private ObjectMapper objectMapper;
    private DynamicTargetScopeWorker worker;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        taskTargetRepo = mock(TaskTargetRepository.class);
        taskDefRepo = mock(TaskDefinitionRepository.class);
        dispatchRecordRepo = mock(TaskDispatchRecordRepository.class);
        scopeResolver = mock(TargetScopeResolver.class);
        outboxService = mock(OutboxService.class);
        MeterRegistry registry = new SimpleMeterRegistry();
        metrics = new ScopeMetrics(registry);
        objectMapper = new ObjectMapper();

        worker = new DynamicTargetScopeWorker(
                jdbcTemplate, taskTargetRepo, taskDefRepo,
                dispatchRecordRepo, scopeResolver, outboxService, metrics, objectMapper);
    }

    @Test
    void scan_noDynamicTargets_noOp() {
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class))).thenReturn(List.of());

        worker.scan();

        assertEquals(1, metrics.scanCounter().count());
        assertEquals(0, metrics.newcomerCounter().count());
        verifyNoInteractions(taskTargetRepo);
    }

    @Test
    void processTarget_newVins_createsDispatchRecordsAndOutbox() {
        TaskTarget target = buildDynamicTarget();
        when(taskTargetRepo.findById(1L)).thenReturn(Optional.of(target));

        when(scopeResolver.resolve(eq("all"), anyString(), eq("default")))
                .thenReturn(List.of("LSVWA234567890123", "LSVWB234567890456", "LSVWC234567890789"));
        when(dispatchRecordRepo.findExistingVinsByTaskId("tsk_001"))
                .thenReturn(Set.of("LSVWA234567890123")); // 已存在 1 辆

        worker.processTarget(1L);

        // 应创建 2 个新 dispatch_record
        verify(dispatchRecordRepo).saveAll(argThat(records -> {
            List<TaskDispatchRecord> list = (List<TaskDispatchRecord>) records;
            return list.size() == 2
                    && list.get(0).getVin().equals("LSVWB234567890456")
                    && list.get(1).getVin().equals("LSVWC234567890789");
        }));

        // 应入队 2 个 outbox 事件
        verify(outboxService, times(2)).enqueueDispatchCommand(anyString(), anyString(), any());

        // 应更新扫描元数据
        verify(taskTargetRepo).save(argThat(t ->
                t.getDynamicLastResolvedAt() != null && t.getDynamicResolutionCount() == 1));

        assertEquals(2.0, metrics.newcomerCounter().count());
    }

    @Test
    void processTarget_noNewVins_onlyUpdatesMetadata() {
        TaskTarget target = buildDynamicTarget();
        when(taskTargetRepo.findById(1L)).thenReturn(Optional.of(target));

        when(scopeResolver.resolve(eq("all"), anyString(), eq("default")))
                .thenReturn(List.of("LSVWA234567890123"));
        when(dispatchRecordRepo.findExistingVinsByTaskId("tsk_001"))
                .thenReturn(Set.of("LSVWA234567890123")); // 已全部存在

        worker.processTarget(1L);

        verify(dispatchRecordRepo, never()).saveAll(any());
        verify(outboxService, never()).enqueueDispatchCommand(anyString(), anyString(), any());
        verify(taskTargetRepo).save(argThat(t ->
                t.getDynamicLastResolvedAt() != null && t.getDynamicResolutionCount() == 1));
    }

    @Test
    void processTarget_taskNotActive_skips() {
        TaskTarget target = buildDynamicTarget();
        target.getTaskDefinition().setStatus("paused");
        when(taskTargetRepo.findById(1L)).thenReturn(Optional.of(target));

        worker.processTarget(1L);

        verifyNoInteractions(scopeResolver);
        verifyNoInteractions(dispatchRecordRepo);
    }

    @Test
    void processTarget_taskExpired_skips() {
        TaskTarget target = buildDynamicTarget();
        target.getTaskDefinition().setValidUntil(LocalDateTime.now().minusDays(1));
        when(taskTargetRepo.findById(1L)).thenReturn(Optional.of(target));

        worker.processTarget(1L);

        verifyNoInteractions(scopeResolver);
        verifyNoInteractions(dispatchRecordRepo);
    }

    @Test
    void processTarget_targetNotFound_noOp() {
        when(taskTargetRepo.findById(1L)).thenReturn(Optional.empty());

        worker.processTarget(1L);

        verifyNoInteractions(scopeResolver);
    }

    @Test
    void processTarget_emptyResolution_onlyUpdatesMetadata() {
        TaskTarget target = buildDynamicTarget();
        when(taskTargetRepo.findById(1L)).thenReturn(Optional.of(target));

        when(scopeResolver.resolve(eq("all"), anyString(), eq("default")))
                .thenReturn(List.of());

        worker.processTarget(1L);

        verify(dispatchRecordRepo, never()).saveAll(any());
        verify(taskTargetRepo).save(argThat(t ->
                t.getDynamicLastResolvedAt() != null && t.getDynamicResolutionCount() == 1));
    }

    @Test
    void scan_exceptionDuringProcessing_recordsError() {
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class)))
                .thenReturn(List.of(1L));
        when(taskTargetRepo.findById(1L))
                .thenThrow(new RuntimeException("DB 连接失败"));

        // 不应抛出异常
        worker.scan();

        assertEquals(1, metrics.errorCounter().count());
        assertEquals(1, metrics.scanCounter().count());
    }

    @Test
    void processTarget_firstScan_allVinsAreNewcomers() {
        // 首次扫描:dynamicLastResolvedAt 为 null
        TaskTarget target = buildDynamicTarget();
        target.setDynamicLastResolvedAt(null);
        target.setDynamicResolutionCount(0);
        when(taskTargetRepo.findById(1L)).thenReturn(Optional.of(target));

        when(scopeResolver.resolve(eq("all"), anyString(), eq("default")))
                .thenReturn(List.of("LSVWA234567890123", "LSVWB234567890456"));
        when(dispatchRecordRepo.findExistingVinsByTaskId("tsk_001"))
                .thenReturn(Set.of()); // 无已有记录

        worker.processTarget(1L);

        // 全部 2 辆都是新 VIN
        verify(dispatchRecordRepo).saveAll(argThat(records -> {
            List<TaskDispatchRecord> list = (List<TaskDispatchRecord>) records;
            return list.size() == 2;
        }));
        assertEquals(2.0, metrics.newcomerCounter().count());
    }

    @Test
    void processTarget_taskWithValidUntilInFuture_proceeds() {
        TaskTarget target = buildDynamicTarget();
        target.getTaskDefinition().setValidUntil(LocalDateTime.now().plusDays(30));
        when(taskTargetRepo.findById(1L)).thenReturn(Optional.of(target));

        when(scopeResolver.resolve(eq("all"), anyString(), eq("default")))
                .thenReturn(List.of("LSVWA234567890123"));
        when(dispatchRecordRepo.findExistingVinsByTaskId("tsk_001"))
                .thenReturn(Set.of());

        worker.processTarget(1L);

        verify(dispatchRecordRepo).saveAll(any());
    }

    @Test
    void processTarget_incrementalScan_respectsLastResolvedAt() {
        TaskTarget target = buildDynamicTarget();
        target.setDynamicLastResolvedAt(LocalDateTime.now().minusHours(1));
        target.setDynamicResolutionCount(3);
        when(taskTargetRepo.findById(1L)).thenReturn(Optional.of(target));

        when(scopeResolver.resolve(eq("all"), anyString(), eq("default")))
                .thenReturn(List.of("LSVWA234567890123", "LSVVB234567890456"));
        when(dispatchRecordRepo.findExistingVinsByTaskId("tsk_001"))
                .thenReturn(Set.of("LSVWA234567890123"));

        worker.processTarget(1L);

        // 只有 1 个新 VIN
        verify(dispatchRecordRepo).saveAll(argThat(records -> {
            List<TaskDispatchRecord> list = (List<TaskDispatchRecord>) records;
            return list.size() == 1 && list.get(0).getVin().equals("LSVVB234567890456");
        }));

        // resolutionCount 应从 3 递增到 4
        verify(taskTargetRepo).save(argThat(t -> t.getDynamicResolutionCount() == 4));
    }

    // --- 辅助方法 ---

    private TaskTarget buildDynamicTarget() {
        TaskDefinition def = new TaskDefinition();
        def.setTaskId("tsk_001");
        def.setTenantId("default");
        def.setStatus("active");
        def.setPriority(5);
        def.setScheduleType("periodic");
        def.setDiagPayload("{\"ecuScope\":[\"VCU\",\"BMS\"]}");
        def.setPayloadHash("abc123");

        TaskTarget target = new TaskTarget();
        target.setId(1L);
        target.setTaskDefinition(def);
        target.setTargetType("all");
        target.setTargetValue("{}");
        target.setMode("dynamic");
        target.setDynamicResolutionCount(0);
        return target;
    }
}

package com.opendota.conformance;

import com.opendota.OpenDotaApplication;
import com.opendota.task.entity.TaskDefinition;
import com.opendota.task.entity.TaskDispatchRecord;
import com.opendota.task.repository.TaskDefinitionRepository;
import com.opendota.task.repository.TaskDispatchRecordRepository;
import com.opendota.task.revise.TaskReviseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 D 组符合性测试 — 任务 supersedes(协议 §12.6.3 / 附录 D D-1/D-2/D-3)。
 *
 * <p>三条用例对应 SupersedeDecider 的三个分支:
 * <ul>
 *   <li><strong>D-1</strong> 修订 queued 任务: dispatch_status='pending_online' → DIRECT_REPLACE
 *       → 旧 dispatch_record 改 canceled,新任务 dispatchedCount &gt; 0</li>
 *   <li><strong>D-2</strong> 修订正在执行的可中断任务: dispatch_status='dispatched' + 普通 payload
 *       → MARK_SUPERSEDING → 旧 dispatch_record 改 superseding,supersededBy 指向新任务</li>
 *   <li><strong>D-3</strong> 修订正在执行不可中断宏: dispatch_status='dispatched' +
 *       diag_payload.macroType='macro_data_transfer' → REJECT
 *       → 响应 rejectedCount=1,旧记录保持 dispatched,业务层不抛错(用 response 字段表达)</li>
 * </ul>
 *
 * <p>本测试与 {@code TaskReviseServiceTest} 互补:单测在 mock 层覆盖 D-1/D-2/D-3,
 * 本测试在真实 Spring + PG 上跑端到端,验证持久化、约束(uniq/RLS/触发器)、事务边界。
 *
 * <p>依赖:本地 docker-compose(PG 5432)在线 + Flyway 迁移 V1..V10 已应用。
 * 启用方式:{@code mvn -Pconformance test -Dopendota.conformance.enabled=true}
 */
@Tag("conformance")
@EnabledIfSystemProperty(named = "opendota.conformance.enabled", matches = "true")
@ActiveProfiles("dev")
@SpringBootTest(
        classes = OpenDotaApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.locations=classpath:db/migration,classpath:db/dev",
                "opendota.mqtt.client-id-prefix=opendota-conformance-supersede"
        })
class Phase4SupersedeConformanceTest {

    private static final String VIN = "LSVWA234567890123";
    private static final String TENANT = "default-tenant";
    private static final String OPERATOR = "operator-conformance";

    @Autowired private TaskReviseService taskReviseService;
    @Autowired private TaskDefinitionRepository taskDefRepo;
    @Autowired private TaskDispatchRecordRepository dispatchRecordRepo;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String oldTaskId;
    private String runTag;

    @BeforeEach
    void seedClean() {
        runTag = "p4d_" + UUID.randomUUID().toString().substring(0, 8);
        oldTaskId = "tsk_" + runTag + "_old";
        // 清理本测试上一轮的残留(避免 unique 冲突)
        jdbcTemplate.update("DELETE FROM task_dispatch_record WHERE task_id LIKE ?",
                "tsk_p4d_%");
        jdbcTemplate.update("DELETE FROM task_definition WHERE task_id LIKE ?",
                "tsk_p4d_%");
    }

    /**
     * D-1 修订队列中任务:旧 dispatch_record 改 canceled,新任务 dispatched。
     */
    @Test
    @DisplayName("D-1: supersedes queued task → DIRECT_REPLACE, old canceled")
    void d1_revisionOnQueuedTask_directReplace() {
        seedTask(oldTaskId, "{\"ecuScope\":[\"VCU\"]}", "pending_online");

        TaskReviseService.ReviseTaskRequest req =
                new TaskReviseService.ReviseTaskRequest("D-1 新任务", 5, null, null);
        TaskReviseService.ReviseTaskResponse resp =
                taskReviseService.reviseTask(oldTaskId, req, OPERATOR, TENANT);

        // 新任务上线
        assertNotEquals(oldTaskId, resp.taskId());
        assertEquals(2, resp.version());
        assertEquals(oldTaskId, resp.supersedesTaskId());
        assertTrue(resp.dispatchedCount() >= 1, "D-1 至少 1 条新 dispatch_record");
        assertEquals(0, resp.rejectedCount(), "D-1 不应有 rejected");

        // 旧 dispatch_record 终态 canceled,supersededBy 指向新任务
        var oldRecord = dispatchRecordRepo.findByTaskIdAndVin(oldTaskId, VIN).orElseThrow();
        assertEquals("canceled", oldRecord.getDispatchStatus(),
                "D-1 旧记录必须改为 canceled");
        assertEquals(resp.taskId(), oldRecord.getSupersededBy());

        // 旧 task_definition 状态 canceled
        TaskDefinition oldDef = taskDefRepo.findByTaskId(oldTaskId).orElseThrow();
        assertEquals("canceled", oldDef.getStatus());
    }

    /**
     * D-2 修订正在执行的常规任务:旧记录改 superseding,supersededBy 指向新任务。
     */
    @Test
    @DisplayName("D-2: supersedes executing routine_wait → MARK_SUPERSEDING")
    void d2_revisionOnRoutineWaitTask_markSuperseding() {
        seedTask(oldTaskId,
                "{\"ecuScope\":[\"VCU\"],\"macroType\":\"macro_routine_wait\"}",
                "dispatched");

        TaskReviseService.ReviseTaskRequest req =
                new TaskReviseService.ReviseTaskRequest("D-2 新任务", 5, null, null);
        TaskReviseService.ReviseTaskResponse resp =
                taskReviseService.reviseTask(oldTaskId, req, OPERATOR, TENANT);

        assertTrue(resp.dispatchedCount() >= 1, "D-2 应入队 ≥1 条新 dispatch");
        assertEquals(0, resp.rejectedCount(), "D-2 routine_wait 是可中断宏,不应被 reject");

        var oldRecord = dispatchRecordRepo.findByTaskIdAndVin(oldTaskId, VIN).orElseThrow();
        assertEquals("superseding", oldRecord.getDispatchStatus(),
                "D-2 旧记录必须改为 superseding(等待车端确认)");
        assertEquals(resp.taskId(), oldRecord.getSupersededBy());
    }

    /**
     * D-3 修订正在执行的不可中断宏 macro_data_transfer:返回 rejected,旧记录不动。
     */
    @Test
    @DisplayName("D-3: supersedes executing macro_data_transfer → REJECT")
    void d3_revisionOnDataTransfer_rejected() {
        seedTask(oldTaskId,
                "{\"ecuScope\":[\"VCU\"],\"macroType\":\"macro_data_transfer\"}",
                "dispatched");

        TaskReviseService.ReviseTaskRequest req =
                new TaskReviseService.ReviseTaskRequest("D-3 新任务", 5, null, null);
        TaskReviseService.ReviseTaskResponse resp =
                taskReviseService.reviseTask(oldTaskId, req, OPERATOR, TENANT);

        assertEquals(0, resp.dispatchedCount(),
                "D-3 不可中断宏期间不下发新任务");
        assertEquals(1, resp.rejectedCount(), "D-3 应明确 reject");
        assertTrue(resp.rejectedVins().contains(VIN));

        var oldRecord = dispatchRecordRepo.findByTaskIdAndVin(oldTaskId, VIN).orElseThrow();
        assertEquals("dispatched", oldRecord.getDispatchStatus(),
                "D-3 旧记录必须保持 dispatched,不被中断");
        assertEquals(resp.taskId(), oldRecord.getSupersededBy(),
                "supersededBy 仍记录新任务,等不可中断宏完成后由车端 ack 触发回放");
    }

    // ========== 辅助:直接 JDBC 注入测试数据 ==========

    /**
     * 注入一条 task_definition + 一条 task_dispatch_record。
     */
    private void seedTask(String taskId, String diagPayloadJson, String dispatchStatus) {
        jdbcTemplate.update("""
                INSERT INTO task_definition (
                    task_id, tenant_id, task_name, version, priority,
                    schedule_type, schedule_config, miss_policy,
                    payload_type, diag_payload, payload_hash, status, created_by, created_at, updated_at
                ) VALUES (?, ?, ?, 1, 5, 'periodic', ?::jsonb, 'skip_all',
                          'batch', ?::jsonb, 'phase4doD-test-hash', 'active', ?, NOW(), NOW())
                """,
                taskId, TENANT, "Phase4-DoD-" + runTag,
                "{\"cronExpression\":\"0 */5 * * * ?\"}",
                diagPayloadJson, OPERATOR);

        jdbcTemplate.update("""
                INSERT INTO task_dispatch_record (
                    task_id, tenant_id, vin, ecu_scope, dispatch_status,
                    retry_count, current_execution_count, created_at
                ) VALUES (?, ?, ?, '[\"VCU\"]'::jsonb, ?, 0, 0, NOW())
                """,
                taskId, TENANT, VIN, dispatchStatus);
    }
}

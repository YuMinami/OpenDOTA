package com.opendota.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opendota.OpenDotaApplication;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.task.execution.ExecutionBeginHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 端到端符合性测试 — 周期任务双 ack。
 *
 * <p>覆盖 Phase 4 DoD #2 + Step 4.5 验收项:
 * <ul>
 *   <li><strong>DoD #2</strong>: 创建 periodic 任务 → 多次 execution_begin → task_execution_log
 *       累计行数符合期望(原文要求 cron 五分钟一次,十分钟内累计 2 行,
 *       本测试以 1Hz 加速为 10 次连续执行,等价覆盖)</li>
 *   <li><strong>Step 4.5</strong>: 周期任务跑 10 次,{@code current_execution_count=10};
 *       两次相同 execution_begin 写入只有一行(幂等)</li>
 * </ul>
 *
 * <p>实现思路:绕过 MQTT/Kafka,直接以 ExecutionBeginHandler 模拟车端连续上报
 * (协议 §8.5.1 cloud 的接收侧逻辑)。这样测试在 conformance profile 下需要 PG 但不需要
 * EMQX/Kafka 容器,降低本地开发依赖。
 *
 * <p>启用方式:{@code mvn -Pconformance test -Dopendota.conformance.enabled=true}
 */
@Tag("conformance")
@EnabledIfSystemProperty(named = "opendota.conformance.enabled", matches = "true")
@ActiveProfiles("dev")
@SpringBootTest(
        classes = OpenDotaApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.locations=classpath:db/migration,classpath:db/dev",
                "opendota.mqtt.client-id-prefix=opendota-conformance-periodic"
        })
class Phase4PeriodicEndToEndConformanceTest {

    private static final String VIN = "LSVWA234567890123";
    private static final String TENANT = "default-tenant";
    private static final String OPERATOR = "operator-conformance";

    @Autowired private ExecutionBeginHandler executionBeginHandler;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private String taskId;
    private String runTag;

    @BeforeEach
    void seed() {
        runTag = "p4ete_" + UUID.randomUUID().toString().substring(0, 8);
        taskId = "tsk_" + runTag;

        // 清理上一轮残留
        jdbcTemplate.update("DELETE FROM task_execution_log WHERE task_id LIKE 'tsk_p4ete_%'");
        jdbcTemplate.update("DELETE FROM task_dispatch_record WHERE task_id LIKE 'tsk_p4ete_%'");
        jdbcTemplate.update("DELETE FROM task_definition WHERE task_id LIKE 'tsk_p4ete_%'");

        // 注入 periodic 任务定义 + 1 条 dispatched 记录
        jdbcTemplate.update("""
                INSERT INTO task_definition (
                    task_id, tenant_id, task_name, version, priority,
                    schedule_type, schedule_config, miss_policy,
                    payload_type, diag_payload, payload_hash, status, created_by, created_at, updated_at
                ) VALUES (?, ?, ?, 1, 5, 'periodic', ?::jsonb, 'skip_all',
                          'batch', '{\"ecuScope\":[\"VCU\"]}'::jsonb,
                          'phase4-periodic-hash', 'active', ?, NOW(), NOW())
                """,
                taskId, TENANT, "Phase4-周期-" + runTag,
                "{\"cronExpression\":\"0 */5 * * * ?\",\"maxExecutions\":100}",
                OPERATOR);

        jdbcTemplate.update("""
                INSERT INTO task_dispatch_record (
                    task_id, tenant_id, vin, ecu_scope, dispatch_status,
                    retry_count, current_execution_count, created_at, dispatched_at
                ) VALUES (?, ?, ?, '[\"VCU\"]'::jsonb, 'dispatched', 0, 0, NOW(), NOW())
                """,
                taskId, TENANT, VIN);
    }

    /**
     * Step 4.5: 周期任务跑 10 次 → current_execution_count=10,task_execution_log 有 10 行。
     */
    @Test
    @DisplayName("DoD #2 + Step 4.5: 10 次 execution_begin → log=10 行 + count=10")
    void periodic10Executions_persistsCountAndLog() {
        long baseTriggerMs = System.currentTimeMillis();
        for (int seq = 1; seq <= 10; seq++) {
            executionBeginHandler.handle(
                    "dota/v1/event/execution/" + VIN,
                    buildExecutionBegin(seq, baseTriggerMs + seq * 1000L));
        }

        // 1) execution_log 必须有 10 行
        Integer logCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_execution_log WHERE task_id = ? AND vin = ?",
                Integer.class, taskId, VIN);
        assertEquals(10, logCount, "10 次 execution_begin 应产生 10 行 task_execution_log");

        // 2) seq 1..10 各一行
        Integer distinctSeqs = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT execution_seq) FROM task_execution_log WHERE task_id = ?",
                Integer.class, taskId);
        assertEquals(10, distinctSeqs, "execution_seq 应覆盖 1..10");

        // 3) current_execution_count = 10(GREATEST 累计 + V4 触发器单调保护)
        Integer currentCount = jdbcTemplate.queryForObject(
                "SELECT current_execution_count FROM task_dispatch_record WHERE task_id = ? AND vin = ?",
                Integer.class, taskId, VIN);
        assertEquals(10, currentCount,
                "Step 4.5 验收: current_execution_count 应累计为 10");

        // 4) 同时验证 DoD #2: 至少 2 行(原文"10 分钟内 2 行")
        assertTrue(logCount >= 2,
                "DoD #2 验收: task_execution_log 至少 2 行(本测试 10 行,远超阈值)");
    }

    /**
     * Step 4.5: 模拟器发两次相同 execution_begin → PG 只有一行(ON CONFLICT DO NOTHING 幂等)。
     */
    @Test
    @DisplayName("Step 4.5: 重复 execution_begin 幂等 → PG 单行")
    void duplicateExecutionBegin_idempotent() {
        long triggerAt = System.currentTimeMillis();
        DiagMessage<ObjectNode> envelope = buildExecutionBegin(7, triggerAt);

        executionBeginHandler.handle("dota/v1/event/execution/" + VIN, envelope);
        executionBeginHandler.handle("dota/v1/event/execution/" + VIN, envelope);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_execution_log WHERE task_id = ? AND vin = ? AND execution_seq = 7",
                Integer.class, taskId, VIN);
        assertEquals(1, rows, "相同 (taskId, vin, executionSeq) 的两次上报应幂等,只插一行");

        Integer currentCount = jdbcTemplate.queryForObject(
                "SELECT current_execution_count FROM task_dispatch_record WHERE task_id = ? AND vin = ?",
                Integer.class, taskId, VIN);
        assertEquals(7, currentCount,
                "GREATEST 即使被多次 UPDATE 也不会回退,应保持 7");
    }

    /**
     * Step 4.5: 乱序到达(seq=10 先于 seq=1)→ count 不回退(V4 触发器保底)。
     */
    @Test
    @DisplayName("Step 4.5: out-of-order 不回退 current_execution_count")
    void outOfOrderExecutionBegin_countMonotonic() {
        long baseAt = System.currentTimeMillis();
        // 先发 10
        executionBeginHandler.handle("dota/v1/event/execution/" + VIN,
                buildExecutionBegin(10, baseAt));
        // 后发 1(模拟旧消息晚到)
        executionBeginHandler.handle("dota/v1/event/execution/" + VIN,
                buildExecutionBegin(1, baseAt - 60_000L));

        Integer currentCount = jdbcTemplate.queryForObject(
                "SELECT current_execution_count FROM task_dispatch_record WHERE task_id = ? AND vin = ?",
                Integer.class, taskId, VIN);
        assertEquals(10, currentCount, "GREATEST 保证 count 单调不减,应保持 10");
    }

    private DiagMessage<ObjectNode> buildExecutionBegin(int seq, long triggerAt) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("taskId", taskId);
        payload.put("executionSeq", seq);
        payload.put("triggerAt", triggerAt);
        payload.put("triggerSource", "cron");
        // msgId 必须不同才能避免 V4 ON CONFLICT 之外的 unique 冲突
        return new DiagMessage<>(
                "msg-p4ete-" + UUID.randomUUID(),
                System.currentTimeMillis(),
                VIN,
                DiagAction.EXECUTION_BEGIN,
                null, null,
                payload);
    }
}

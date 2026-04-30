package com.opendota.task.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;

/**
 * task_execution_log 表数据访问层。
 *
 * <p>使用 JdbcTemplate 直接操作(同 lifecycle 包模式),因为该表启用 RLS,
 * 需要在事务内通过 {@code SET LOCAL app.tenant_id} 注入租户上下文。
 */
@Repository
public class TaskExecutionLogRepository {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionLogRepository.class);

    private final JdbcTemplate jdbc;

    public TaskExecutionLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入 execution_begin 记录(幂等: ON CONFLICT DO NOTHING)。
     *
     * <p>对应协议 §8.5.1 云端处理伪代码:
     * <pre>
     * INSERT INTO task_execution_log (task_id, vin, execution_seq, trigger_time, begin_msg_id, begin_reported_at)
     * VALUES (?, ?, ?, ?, ?, now())
     * ON CONFLICT (task_id, vin, execution_seq) DO NOTHING;
     * </pre>
     *
     * @return affected rows (0 = 幂等去重, 1 = 新插入)
     */
    public int insertBegin(String tenantId, String taskId, String vin, int executionSeq,
                           long triggerAt, String beginMsgId) {
        String sql = """
                INSERT INTO task_execution_log
                    (tenant_id, task_id, vin, execution_seq, trigger_time, begin_msg_id, begin_reported_at)
                VALUES (?, ?, ?, ?, to_timestamp(? / 1000.0), ?, now())
                ON CONFLICT (task_id, vin, execution_seq) DO NOTHING
                """;
        int affected = jdbc.update(sql, tenantId, taskId, vin, executionSeq, triggerAt, beginMsgId);
        if (affected == 0) {
            log.debug("execution_begin 幂等去重 taskId={} vin={} seq={}", taskId, vin, executionSeq);
        }
        return affected;
    }

    /**
     * 回填 execution_end 信息。
     *
     * <p>对应协议 §8.5.1:
     * <pre>
     * UPDATE task_execution_log
     * SET end_msg_id = ?, end_reported_at = now(),
     *     overall_status = ?, result_payload = ?::jsonb, execution_duration = ?
     * WHERE task_id = ? AND vin = ? AND execution_seq = ?;
     * </pre>
     *
     * @return affected rows (0 = 对应 begin 未找到或已回填)
     */
    public int updateEnd(String endMsgId, Integer overallStatus, String resultPayloadJson,
                         Integer executionDuration, String taskId, String vin, int executionSeq) {
        String sql = """
                UPDATE task_execution_log
                SET end_msg_id = ?,
                    end_reported_at = now(),
                    overall_status = ?,
                    result_payload = ?::jsonb,
                    execution_duration = ?
                WHERE task_id = ? AND vin = ? AND execution_seq = ?
                  AND end_reported_at IS NULL
                """;
        return jdbc.update(sql,
                endMsgId,
                overallStatus,
                resultPayloadJson,
                executionDuration,
                taskId, vin, executionSeq);
    }

    /**
     * 查询超时未结束的 execution_begin 记录。
     *
     * <p>条件: {@code end_reported_at IS NULL} 且 {@code begin_reported_at + maxExecutionMs * 3 < now()}。
     * 通过 JOIN task_definition 获取 schedule_config 中的 maxExecutionMs。
     *
     * @return 超时记录列表 [{taskId, vin, executionSeq, beginReportedAt, tenantId}]
     */
    public List<ExecutionBeginRecord> findTimedOutExecutions() {
        String sql = """
                SELECT tel.task_id, tel.vin, tel.execution_seq, tel.begin_reported_at, tel.tenant_id
                FROM task_execution_log tel
                JOIN task_definition td ON td.task_id = tel.task_id
                WHERE tel.end_reported_at IS NULL
                  AND tel.begin_reported_at IS NOT NULL
                  AND (td.schedule_config->>'maxExecutionMs')::bigint IS NOT NULL
                  AND (td.schedule_config->>'maxExecutionMs')::bigint > 0
                  AND tel.begin_reported_at
                      + make_interval(secs => (td.schedule_config->>'maxExecutionMs')::bigint * 3 / 1000.0)
                      < now()
                """;
        return jdbc.query(sql, (rs, rowNum) -> new ExecutionBeginRecord(
                rs.getString("task_id"),
                rs.getString("vin"),
                rs.getInt("execution_seq"),
                rs.getTimestamp("begin_reported_at").toLocalDateTime(),
                rs.getString("tenant_id")
        ));
    }

    /**
     * 标记超时的 execution 为 failed。
     *
     * @return affected rows
     */
    public int markTimedOutAsFailed(String taskId, String vin, int executionSeq, String tenantId) {
        String sql = """
                UPDATE task_execution_log
                SET overall_status = 2,
                    end_reported_at = now(),
                    result_payload = '{"error":"EXECUTION_TIMEOUT","msg":"begin 后超过 maxExecutionMs*3 未收到 end"}'::jsonb
                WHERE task_id = ? AND vin = ? AND execution_seq = ?
                  AND end_reported_at IS NULL
                """;
        return jdbc.update(sql, taskId, vin, executionSeq);
    }

    public record ExecutionBeginRecord(
            String taskId,
            String vin,
            int executionSeq,
            LocalDateTime beginReportedAt,
            String tenantId) {
    }
}

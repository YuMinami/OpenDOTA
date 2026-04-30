package com.opendota.task.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
     * 任务执行日志分页查询(REST §6.5)。
     *
     * <p>排序: {@code execution_seq DESC, trigger_time DESC}(最新一次执行在前)。
     * <p>{@code vin} 可空: 为空时返回任务下所有车辆的执行日志,非空时仅返回该 VIN 的记录。
     * <p>tenant_id 显式过滤,与 PG RLS 互为防御。
     */
    public Page<ExecutionLogRow> findByTaskId(String tenantId, String taskId, String vin, Pageable pageable) {
        StringBuilder where = new StringBuilder("WHERE tenant_id = ? AND task_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(taskId);
        if (vin != null && !vin.isBlank()) {
            where.append(" AND vin = ?");
            params.add(vin);
        }

        // 先查 total,避免分页越界后仍执行 OFFSET 巨大值
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_execution_log " + where,
                Long.class,
                params.toArray());
        long totalElements = total == null ? 0L : total;

        if (totalElements == 0 || pageable.getOffset() >= totalElements) {
            return new PageImpl<>(List.of(), pageable, totalElements);
        }

        String sql = """
                SELECT execution_seq, vin, trigger_time, overall_status, execution_duration,
                       begin_reported_at, end_reported_at, miss_compensation, result_payload
                FROM task_execution_log
                """
                + where
                + " ORDER BY execution_seq DESC, trigger_time DESC LIMIT ? OFFSET ?";

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(pageable.getPageSize());
        queryParams.add(pageable.getOffset());

        List<ExecutionLogRow> content = jdbc.query(sql, (rs, rowNum) -> new ExecutionLogRow(
                rs.getInt("execution_seq"),
                rs.getString("vin"),
                rs.getTimestamp("trigger_time"),
                (Integer) rs.getObject("overall_status"),
                (Integer) rs.getObject("execution_duration"),
                rs.getTimestamp("begin_reported_at"),
                rs.getTimestamp("end_reported_at"),
                rs.getString("miss_compensation"),
                rs.getString("result_payload")
        ), queryParams.toArray());

        return new PageImpl<>(content, pageable, totalElements);
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

    /**
     * 分页查询行记录(REST §6.5)。
     *
     * <p>时间字段保留 {@link Timestamp} 形式,由 service 层负责转 epoch ms,
     * 与 {@code TaskController} 现有 {@code validFrom} 转换风格保持一致。
     * JSONB 字段 {@code missCompensation/resultPayload} 以原始 JSON 字符串透传,
     * controller 层反序列化成 {@code Object} 输出。
     */
    public record ExecutionLogRow(
            int executionSeq,
            String vin,
            Timestamp triggerTime,
            Integer overallStatus,
            Integer executionDuration,
            Timestamp beginReportedAt,
            Timestamp endReportedAt,
            String missCompensationJson,
            String resultPayloadJson) {
    }
}

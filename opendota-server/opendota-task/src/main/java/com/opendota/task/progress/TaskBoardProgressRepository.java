package com.opendota.task.progress;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * v_task_board_progress 视图查询(架构 §4.8.1)。
 *
 * <p>视图按 {@code task_id} 聚合 {@code task_dispatch_record} 的 dispatch_status,
 * 输出 4 终态计数 + 总计 + {@code board_status} 看板状态。
 *
 * <p>使用 JdbcTemplate 而非 JPA 是因为视图无主键、不属于 JPA 实体管理范畴,
 * 且与 {@link com.opendota.task.execution.TaskExecutionLogRepository} 现有
 * RLS 模式保持一致。
 */
@Repository
public class TaskBoardProgressRepository {

    private final JdbcTemplate jdbc;

    public TaskBoardProgressRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询任务在看板视图中的进度行。
     *
     * <p>视图的 GROUP BY 按 task_id 进行,因此返回 0 行(全新任务还没有 dispatch_record)
     * 或 1 行。返回 {@link Optional#empty()} 时调用方应兜底为「全 0 + in_progress」。
     */
    public Optional<BoardProgressRow> findByTaskId(String taskId) {
        String sql = """
                SELECT task_id,
                       completed_cnt,
                       failed_cnt,
                       expired_cnt,
                       canceled_cnt,
                       total_cnt,
                       board_status
                FROM v_task_board_progress
                WHERE task_id = ?
                """;
        return jdbc.query(sql, rs -> {
            if (!rs.next()) {
                return Optional.<BoardProgressRow>empty();
            }
            return Optional.of(new BoardProgressRow(
                    rs.getString("task_id"),
                    rs.getInt("completed_cnt"),
                    rs.getInt("failed_cnt"),
                    rs.getInt("expired_cnt"),
                    rs.getInt("canceled_cnt"),
                    rs.getInt("total_cnt"),
                    rs.getString("board_status")));
        }, taskId);
    }

    /**
     * v_task_board_progress 视图行映射。
     */
    public record BoardProgressRow(
            String taskId,
            int completedCnt,
            int failedCnt,
            int expiredCnt,
            int canceledCnt,
            int totalCnt,
            String boardStatus) {
    }
}

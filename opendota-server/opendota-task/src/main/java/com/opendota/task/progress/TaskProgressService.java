package com.opendota.task.progress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.task.entity.TaskDefinition;
import com.opendota.task.execution.TaskExecutionLogRepository;
import com.opendota.task.execution.TaskExecutionLogRepository.ExecutionLogRow;
import com.opendota.task.model.DispatchStatus;
import com.opendota.task.repository.TaskDispatchRecordRepository;
import com.opendota.task.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务进度聚合服务(REST §6.4 / §6.5,Phase 4 Step 4.8)。
 *
 * <p>职责:
 * <ul>
 *   <li>{@link #getProgress} 聚合 11 状态分布 + {@code boardStatus} 看板状态 + 完成率</li>
 *   <li>{@link #listExecutions} 任务执行日志分页查询(支持按 VIN 过滤)</li>
 * </ul>
 *
 * <p>所有查询先经由 {@link TaskService#getTask} 校验任务归属租户,跨租户访问统一 404。
 *
 * <p>设计取舍(见任务 PRD ADR):
 * <ul>
 *   <li>{@code completionRate = completed / totalTargets},与 REST §6.4 示例反推一致</li>
 *   <li>{@code boardStatus} 直接读取视图,不在 Java 复算</li>
 *   <li>无 dispatch_record 的任务(刚创建)兜底 {@code in_progress + 全 0}</li>
 * </ul>
 */
@Service
public class TaskProgressService {

    private static final Logger log = LoggerFactory.getLogger(TaskProgressService.class);

    /** REST §6.5 size 上限,防滥用 OFFSET 巨大值 */
    public static final int MAX_PAGE_SIZE = 200;

    private final TaskService taskService;
    private final TaskDispatchRecordRepository dispatchRepo;
    private final TaskBoardProgressRepository boardRepo;
    private final TaskExecutionLogRepository executionLogRepo;
    private final ObjectMapper objectMapper;

    public TaskProgressService(TaskService taskService,
                               TaskDispatchRecordRepository dispatchRepo,
                               TaskBoardProgressRepository boardRepo,
                               TaskExecutionLogRepository executionLogRepo,
                               ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.dispatchRepo = dispatchRepo;
        this.boardRepo = boardRepo;
        this.executionLogRepo = executionLogRepo;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取任务进度聚合(REST §6.4)。
     */
    @Transactional(readOnly = true)
    public TaskProgressView getProgress(String taskId, String tenantId) {
        TaskDefinition def = taskService.getTask(taskId, tenantId);

        // 11 状态分布: 全部填 0,再用 GROUP BY 结果覆盖
        Map<DispatchStatus, Integer> distribution = new EnumMap<>(DispatchStatus.class);
        for (DispatchStatus s : DispatchStatus.values()) {
            distribution.put(s, 0);
        }
        List<Object[]> grouped = dispatchRepo.countByStatusGrouped(taskId);
        for (Object[] row : grouped) {
            String wireName = (String) row[0];
            long count = ((Number) row[1]).longValue();
            try {
                DispatchStatus status = DispatchStatus.of(wireName);
                if (status != null) {
                    distribution.put(status, (int) count);
                }
            } catch (IllegalArgumentException ex) {
                // 历史脏数据(理论上不应出现): 记日志但不破坏聚合
                log.warn("dispatch_status 出现未知值 taskId={} value={}", taskId, wireName);
            }
        }

        // 看板状态 + 总数: 优先取视图(权威),视图无行(全新任务)兜底
        var boardOpt = boardRepo.findByTaskId(taskId);
        int totalTargets;
        String boardStatus;
        if (boardOpt.isPresent()) {
            totalTargets = boardOpt.get().totalCnt();
            boardStatus = boardOpt.get().boardStatus();
        } else {
            totalTargets = 0;
            boardStatus = "in_progress";
        }

        int completed = distribution.get(DispatchStatus.COMPLETED);
        double completionRate = totalTargets == 0 ? 0.0 : (double) completed / totalTargets;

        return new TaskProgressView(
                def.getTaskId(),
                def.getTaskName(),
                def.getVersion(),
                def.getSupersedesTaskId(),
                def.getStatus(),
                totalTargets,
                toOrderedDistribution(distribution),
                completionRate,
                boardStatus);
    }

    /**
     * 任务执行日志分页(REST §6.5)。
     *
     * @param page 1-based 页码(REST 示例 {@code page=1});内部转 0-based 给 Spring Data
     * @param size 每页大小,上限 {@link #MAX_PAGE_SIZE}
     * @param vin  可空,按 VIN 过滤
     */
    @Transactional(readOnly = true)
    public Page<TaskExecutionLogItem> listExecutions(String taskId, String tenantId,
                                                     String vin, int page, int size) {
        // 校验任务归属(404 处理在 getTask 内)
        taskService.getTask(taskId, tenantId);

        int safePage = Math.max(1, page) - 1;             // 1-based → 0-based
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = org.springframework.data.domain.PageRequest.of(safePage, safeSize);

        Page<ExecutionLogRow> rows = executionLogRepo.findByTaskId(tenantId, taskId, vin, pageable);

        List<TaskExecutionLogItem> items = rows.getContent().stream()
                .map(this::toExecutionLogItem)
                .toList();
        return new PageImpl<>(items, pageable, rows.getTotalElements());
    }

    // ========== 内部转换 ==========

    private TaskExecutionLogItem toExecutionLogItem(ExecutionLogRow row) {
        return new TaskExecutionLogItem(
                row.executionSeq(),
                row.vin(),
                toEpochMillis(row.triggerTime()),
                row.overallStatus(),
                row.executionDuration(),
                toEpochMillis(row.beginReportedAt()),
                toEpochMillis(row.endReportedAt()),
                parseJson(row.missCompensationJson()),
                parseJson(row.resultPayloadJson()));
    }

    private Long toEpochMillis(Timestamp ts) {
        if (ts == null) {
            return null;
        }
        // 与 TaskController 现有 LocalDateTime → epochMillis 的 systemDefault 时区保持一致
        return ts.toLocalDateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private Object parseJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException ex) {
            log.warn("解析 execution_log JSONB 失败: {}", ex.getMessage());
            return json;  // 兜底原始字符串,避免整个接口 500
        }
    }

    /**
     * 把 Map 按协议 §14.4.3 状态机顺序输出,前端拿到稳定字段顺序。
     */
    private LinkedHashMap<String, Integer> toOrderedDistribution(Map<DispatchStatus, Integer> src) {
        LinkedHashMap<String, Integer> ordered = new LinkedHashMap<>();
        for (DispatchStatus s : DispatchStatus.values()) {
            ordered.put(s.wireName(), src.getOrDefault(s, 0));
        }
        return ordered;
    }

    // ========== 输出模型 ==========

    /**
     * GET /api/task/{taskId}/progress 响应体(REST §6.4)。
     *
     * <p>{@code progress} 用 {@link LinkedHashMap} 保证字段顺序与状态机文档一致。
     */
    public record TaskProgressView(
            String taskId,
            String taskName,
            Integer version,
            String supersedesTaskId,
            String status,
            int totalTargets,
            LinkedHashMap<String, Integer> progress,
            double completionRate,
            String boardStatus) {
    }

    /**
     * GET /api/task/{taskId}/executions 单行(REST §6.5)。
     */
    public record TaskExecutionLogItem(
            int executionSeq,
            String vin,
            Long triggerTime,
            Integer overallStatus,
            Integer executionDuration,
            Long beginReportedAt,
            Long endReportedAt,
            Object missCompensation,
            Object resultPayload) {
    }
}

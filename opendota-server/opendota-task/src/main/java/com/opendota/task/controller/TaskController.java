package com.opendota.task.controller;

import com.opendota.common.envelope.Operator;
import com.opendota.common.web.ApiResponse;
import com.opendota.diag.api.OperatorContextResolver;
import com.opendota.task.entity.TaskDefinition;
import com.opendota.task.progress.TaskProgressService;
import com.opendota.task.progress.TaskProgressService.TaskExecutionLogItem;
import com.opendota.task.progress.TaskProgressService.TaskProgressView;
import com.opendota.task.service.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务定义管理端点(REST §6)。
 *
 * <pre>
 *   POST   /api/task                     → 创建任务
 *   GET    /api/task                     → 分页列表
 *   GET    /api/task/{taskId}            → 单个详情
 *   PUT    /api/task/{taskId}/pause      → 暂停
 *   PUT    /api/task/{taskId}/resume     → 恢复
 *   DELETE /api/task/{taskId}            → 取消
 *   GET    /api/task/{taskId}/versions   → 版本链
 *   GET    /api/task/{taskId}/progress   → 进度聚合(11 状态 + boardStatus)
 *   GET    /api/task/{taskId}/executions → 执行日志分页
 * </pre>
 */
@RestController
@RequestMapping("/api/task")
public class TaskController {

    private final TaskService taskService;
    private final TaskProgressService progressService;
    private final OperatorContextResolver operatorResolver;

    public TaskController(TaskService taskService,
                          TaskProgressService progressService,
                          OperatorContextResolver operatorResolver) {
        this.taskService = taskService;
        this.progressService = progressService;
        this.operatorResolver = operatorResolver;
    }

    /**
     * 创建任务(REST §6.1)。
     */
    @PostMapping
    public ApiResponse<CreateTaskResponse> create(@RequestBody CreateTaskRequest req,
                                                   HttpServletRequest http) {
        Operator operator = operatorResolver.resolve(http);
        CreateTaskResponse resp = taskService.createTask(req, operator.id(), operator.tenantId());
        return ApiResponse.ok(resp);
    }

    /**
     * 分页查询任务列表(REST §6.3)。
     */
    @GetMapping
    public ApiResponse<Page<TaskDefinition>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String scheduleType,
            @RequestParam(required = false) String createdBy,
            @RequestParam(required = false) Integer priority,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest http) {
        Operator operator = operatorResolver.resolve(http);
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<TaskDefinition> result = taskService.listTasks(
                operator.tenantId(), status, scheduleType, createdBy, priority, pageRequest);
        return ApiResponse.ok(result);
    }

    /**
     * 获取单个任务详情(REST §6.3)。
     */
    @GetMapping("/{taskId}")
    public ApiResponse<TaskDetailResponse> get(@PathVariable String taskId,
                                                HttpServletRequest http) {
        Operator operator = operatorResolver.resolve(http);
        TaskDefinition def = taskService.getTask(taskId, operator.tenantId());
        return ApiResponse.ok(toDetailResponse(def));
    }

    /**
     * 暂停任务(REST §6.3)。
     */
    @PutMapping("/{taskId}/pause")
    public ApiResponse<Map<String, String>> pause(@PathVariable String taskId,
                                                    HttpServletRequest http) {
        Operator operator = operatorResolver.resolve(http);
        taskService.pauseTask(taskId, operator.tenantId());
        return ApiResponse.ok(Map.of("taskId", taskId, "status", "paused"));
    }

    /**
     * 恢复任务(REST §6.3)。
     */
    @PutMapping("/{taskId}/resume")
    public ApiResponse<Map<String, String>> resume(@PathVariable String taskId,
                                                     HttpServletRequest http) {
        Operator operator = operatorResolver.resolve(http);
        taskService.resumeTask(taskId, operator.tenantId());
        return ApiResponse.ok(Map.of("taskId", taskId, "status", "active"));
    }

    /**
     * 取消任务(REST §6.3)。
     * 注意: 这是取消任务定义(status→canceled)，不是车端任务取消。
     * 车端任务取消走 {@code DELETE /api/task/vehicle-cancel/{taskId}}。
     */
    @DeleteMapping("/{taskId}")
    public ApiResponse<Map<String, String>> cancel(@PathVariable String taskId,
                                                     HttpServletRequest http) {
        Operator operator = operatorResolver.resolve(http);
        taskService.cancelTask(taskId, operator.tenantId());
        return ApiResponse.ok(Map.of("taskId", taskId, "status", "canceled"));
    }

    /**
     * 获取任务版本链(REST §6.3)。
     */
    @GetMapping("/{taskId}/versions")
    public ApiResponse<List<TaskDefinition>> versions(@PathVariable String taskId,
                                                       HttpServletRequest http) {
        Operator operator = operatorResolver.resolve(http);
        List<TaskDefinition> chain = taskService.getVersionChain(taskId, operator.tenantId());
        return ApiResponse.ok(chain);
    }

    /**
     * 任务进度聚合(REST §6.4)。
     *
     * <p>输出 11 状态分布 + {@code boardStatus} 看板态 + {@code completionRate}。
     * 跨租户访问返回 404(委派给 {@link TaskService#getTask})。
     */
    @GetMapping("/{taskId}/progress")
    public ApiResponse<TaskProgressView> progress(@PathVariable String taskId,
                                                   HttpServletRequest http) {
        Operator operator = operatorResolver.resolve(http);
        TaskProgressView view = progressService.getProgress(taskId, operator.tenantId());
        return ApiResponse.ok(view);
    }

    /**
     * 任务执行日志分页(REST §6.5)。
     *
     * <p>{@code page} 1-based,{@code size} 上限 200(防滥用)。
     * {@code vin} 可空: 不传则返回任务下所有车辆的执行日志。
     */
    @GetMapping("/{taskId}/executions")
    public ApiResponse<Page<TaskExecutionLogItem>> executions(
            @PathVariable String taskId,
            @RequestParam(required = false) String vin,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest http) {
        Operator operator = operatorResolver.resolve(http);
        Page<TaskExecutionLogItem> result = progressService.listExecutions(
                taskId, operator.tenantId(), vin, page, size);
        return ApiResponse.ok(result);
    }

    // ========== 内部 DTO ==========

    /**
     * POST /api/task 请求体(REST §6.1)。
     */
    public record CreateTaskRequest(
            String taskName,
            Integer priority,
            Long validFrom,
            Long validUntil,
            Long executeValidFrom,
            Long executeValidUntil,
            String scheduleType,
            Object scheduleConfig,
            String missPolicy,
            String payloadType,
            Object diagPayload,
            String payloadHash,
            TargetScope targetScope,
            Boolean requiresApproval) {}

    /**
     * 目标范围。
     */
    public record TargetScope(
            String type,
            String mode,
            Object value) {}

    /**
     * POST /api/task 响应体。
     */
    public record CreateTaskResponse(
            String taskId,
            String status,
            int totalTargets,
            String dispatchMode) {}

    /**
     * GET /api/task/{taskId} 响应体。
     */
    public record TaskDetailResponse(
            String taskId,
            String taskName,
            int version,
            String supersedesTaskId,
            Integer priority,
            Long validFrom,
            Long validUntil,
            Long executeValidFrom,
            Long executeValidUntil,
            String scheduleType,
            Object scheduleConfig,
            String missPolicy,
            String payloadType,
            Object diagPayload,
            String payloadHash,
            Boolean requiresApproval,
            String status,
            String createdBy,
            String createdAt,
            String updatedAt) {}

    private TaskDetailResponse toDetailResponse(TaskDefinition def) {
        return new TaskDetailResponse(
                def.getTaskId(),
                def.getTaskName(),
                def.getVersion(),
                def.getSupersedesTaskId(),
                def.getPriority(),
                def.getValidFrom() != null ? def.getValidFrom().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null,
                def.getValidUntil() != null ? def.getValidUntil().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null,
                def.getExecuteValidFrom() != null ? def.getExecuteValidFrom().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null,
                def.getExecuteValidUntil() != null ? def.getExecuteValidUntil().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null,
                def.getScheduleType(),
                def.getScheduleConfig(),
                def.getMissPolicy(),
                def.getPayloadType(),
                def.getDiagPayload(),
                def.getPayloadHash(),
                def.getRequiresApproval(),
                def.getStatus(),
                def.getCreatedBy(),
                def.getCreatedAt() != null ? def.getCreatedAt().toString() : null,
                def.getUpdatedAt() != null ? def.getUpdatedAt().toString() : null);
    }
}

package com.opendota.task.revise;

import com.opendota.common.envelope.Operator;
import com.opendota.common.web.ApiResponse;
import com.opendota.diag.api.OperatorContextResolver;
import com.opendota.task.revise.TaskReviseService.ReviseTaskRequest;
import com.opendota.task.revise.TaskReviseService.ReviseTaskResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务修订端点(REST §6.2、协议 §12.6.3)。
 *
 * <pre>
 *   POST /api/task/{taskId}/revise  → 修订任务(创建新版本并 supersede 旧版本)
 * </pre>
 */
@RestController
@RequestMapping("/api/task")
public class TaskReviseController {

    private final TaskReviseService taskReviseService;
    private final OperatorContextResolver operatorResolver;

    public TaskReviseController(TaskReviseService taskReviseService,
                                OperatorContextResolver operatorResolver) {
        this.taskReviseService = taskReviseService;
        this.operatorResolver = operatorResolver;
    }

    /**
     * 修订任务(POST /api/task/{taskId}/revise)。
     *
     * <p>创建旧任务的新版本(version+1),对旧任务的活跃分发记录按三分支决策处理:
     * <ul>
     *   <li>队列中 → 直接取消旧记录,入队新记录</li>
     *   <li>执行中(常规) → 标记 superseding,入队带 supersedes 字段的新记录</li>
     *   <li>执行中(不可中断宏) → 拒绝修订,新记录标记 pending_backlog</li>
     * </ul>
     */
    @PostMapping("/{taskId}/revise")
    public ApiResponse<ReviseTaskResponse> revise(@PathVariable String taskId,
                                                   @RequestBody ReviseTaskRequest req,
                                                   HttpServletRequest http) {
        Operator operator = operatorResolver.resolve(http);
        ReviseTaskResponse resp = taskReviseService.reviseTask(taskId, req, operator.id(), operator.tenantId());
        return ApiResponse.ok(resp);
    }
}

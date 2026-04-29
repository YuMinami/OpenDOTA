package com.opendota.diag.controller;

import com.opendota.common.envelope.Operator;
import com.opendota.diag.api.OperatorContextResolver;
import com.opendota.diag.dispatch.DiagDispatcher;
import com.opendota.common.web.ApiError;
import com.opendota.common.web.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 任务取消端点(REST §6 + 协议 §12.4)。v1.4 A2 不可中断规则由下游 {@link DiagDispatcher#dispatchTaskCancel}
 * 集中执行,本控制器只做参数校验和 operator 注入。
 *
 * <pre>
 *   DELETE /api/task/{taskId}
 *   Body: { vin, channelId?, reason?, force? }
 * </pre>
 *
 * 当 {@code force=true} 且目标通道正在跑 {@code macro_data_transfer} / {@code macro_security} 时,
 * 下游抛 {@link BusinessException}(code=40305),被 {@code GlobalExceptionHandler} 映射为
 * {@code { "code":40305, "msg":"...", "data":null }}。
 */
@RestController
@RequestMapping("/api/task")
public class TaskCancelController {

    private final DiagDispatcher dispatcher;
    private final OperatorContextResolver operatorResolver;

    public TaskCancelController(DiagDispatcher dispatcher, OperatorContextResolver operatorResolver) {
        this.dispatcher = dispatcher;
        this.operatorResolver = operatorResolver;
    }

    @DeleteMapping("/{taskId}")
    public Map<String, String> cancel(@PathVariable String taskId,
                                      @RequestBody TaskCancelRequest req,
                                      HttpServletRequest http) {
        validate(req);
        Operator operator = operatorResolver.resolve(http);
        String msgId = dispatcher.dispatchTaskCancel(
                req.vin(), taskId, req.channelId(),
                req.reason(), req.force() != null && req.force(), operator);
        return Map.of("msgId", msgId);
    }

    private static void validate(TaskCancelRequest req) {
        if (req == null) throw new BusinessException(ApiError.E40001, "请求 body 必填");
        if (req.vin() == null || req.vin().length() != 17) {
            throw new BusinessException(ApiError.E40001, "vin 必须为 17 位");
        }
    }

    /** DELETE /api/task/{taskId} 请求体。 */
    public record TaskCancelRequest(
            String vin,
            String channelId,
            String reason,
            Boolean force) {}
}

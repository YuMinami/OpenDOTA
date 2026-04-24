package com.opendota.diag.controller;

import com.opendota.common.envelope.Operator;
import com.opendota.diag.api.OperatorContextResolver;
import com.opendota.diag.dispatch.DiagDispatcher;
import com.opendota.diag.web.ApiError;
import com.opendota.diag.web.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 单步诊断指令下发(REST §4.3)。
 *
 * <p>契约:
 * <pre>
 *   POST /api/cmd/single
 *   Headers:
 *     X-Operator-Id, X-Operator-Role, X-Tenant-Id, X-Ticket-Id  (Phase 1 占位,opendota-security 接入后改 JWT)
 *   Body:
 *     { channelId, type, reqData, timeoutMs }
 *   Resp:
 *     { code:0, data:{ msgId:"..." } }
 * </pre>
 *
 * 全异步:HTTP 线程只负责"构 envelope + MQTT publish",结果由 SSE {@code diag-result} 推送。
 */
@RestController
@RequestMapping("/api/cmd")
public class SingleDiagController {

    private final DiagDispatcher dispatcher;
    private final OperatorContextResolver operatorResolver;

    public SingleDiagController(DiagDispatcher dispatcher, OperatorContextResolver operatorResolver) {
        this.dispatcher = dispatcher;
        this.operatorResolver = operatorResolver;
    }

    @PostMapping("/single")
    public Map<String, String> single(@RequestBody SingleCmdRequest req,
                                      HttpServletRequest http) {
        validate(req);
        Operator operator = operatorResolver.resolve(http);
        String msgId = dispatcher.dispatchSingleCmd(
                req.channelId(), req.type(), req.reqData(), req.timeoutMs(), operator);
        return Map.of("msgId", msgId);
    }

    private static void validate(SingleCmdRequest req) {
        if (req == null) {
            throw new BusinessException(ApiError.E40001, "请求 body 必填");
        }
        if (req.channelId() == null || req.channelId().isBlank()) {
            throw new BusinessException(ApiError.E40001, "channelId 必填");
        }
        if (req.reqData() == null || req.reqData().isBlank()) {
            throw new BusinessException(ApiError.E40001, "reqData 必填(UDS PDU hex)");
        }
    }

    /**
     * POST /api/cmd/single 请求体(REST §4.3)。
     *
     * @param channelId 已开的诊断通道 ID
     * @param type      步骤类型,默认 {@code raw_uds};可选宏类型会被 ChannelManager 记录以供 A2 guard 判定
     * @param reqData   UDS PDU 十六进制,如 {@code "22F190"}
     * @param timeoutMs 车端等待 ECU 响应超时毫秒,默认 5000
     */
    public record SingleCmdRequest(
            String channelId,
            String type,
            String reqData,
            Integer timeoutMs) {}
}

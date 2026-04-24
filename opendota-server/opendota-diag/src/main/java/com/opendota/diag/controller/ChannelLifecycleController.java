package com.opendota.diag.controller;

import com.opendota.common.envelope.Operator;
import com.opendota.diag.api.OperatorContextResolver;
import com.opendota.diag.channel.ChannelContext;
import com.opendota.diag.channel.ChannelManager;
import com.opendota.diag.dispatch.DiagDispatcher;
import com.opendota.diag.dispatch.DiagDispatcher.OpenResult;
import com.opendota.diag.web.ApiError;
import com.opendota.diag.web.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 诊断通道生命周期端点(REST §4.1 / §4.2)。
 *
 * <p>与 {@link SingleDiagController} 共同构成 Phase 1 最小可用诊断回路:
 * <ol>
 *   <li>POST /api/channel/open  → 登记通道,下发 channel_open</li>
 *   <li>POST /api/cmd/single   → 在已开的通道上下发 UDS</li>
 *   <li>POST /api/channel/{id}/close → 登记删除,下发 channel_close</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/channel")
public class ChannelLifecycleController {

    private final DiagDispatcher dispatcher;
    private final ChannelManager channelManager;
    private final OperatorContextResolver operatorResolver;

    public ChannelLifecycleController(DiagDispatcher dispatcher, ChannelManager channelManager,
                                       OperatorContextResolver operatorResolver) {
        this.dispatcher = dispatcher;
        this.channelManager = channelManager;
        this.operatorResolver = operatorResolver;
    }

    @PostMapping("/open")
    public Map<String, String> open(@RequestBody ChannelOpenRequest req, HttpServletRequest http) {
        validate(req);
        Operator operator = operatorResolver.resolve(http);
        OpenResult result = dispatcher.dispatchChannelOpen(
                req.vin(), req.ecuName(), req.ecuScope(),
                req.transport(), req.txId(), req.rxId(),
                req.globalTimeoutMs(), req.force() != null && req.force(),
                req.preemptPolicy(), operator);
        return Map.of(
                "channelId", result.channelId(),
                "msgId", result.msgId());
    }

    @PostMapping("/{channelId}/close")
    public Map<String, String> close(@PathVariable String channelId) {
        ChannelContext ctx = channelManager.get(channelId)
                .orElseThrow(() -> new BusinessException(ApiError.E40401,
                        "channel 不存在: " + channelId));
        channelManager.close(channelId);
        return Map.of(
                "channelId", channelId,
                "vin", ctx.getVin());
    }

    private static void validate(ChannelOpenRequest req) {
        if (req == null) throw new BusinessException(ApiError.E40001, "请求 body 必填");
        if (req.vin() == null || req.vin().length() != 17) {
            throw new BusinessException(ApiError.E40001, "vin 必须为 17 位");
        }
        if (req.ecuName() == null || req.ecuName().isBlank()) {
            throw new BusinessException(ApiError.E40001, "ecuName 必填");
        }
        if (req.ecuScope() == null || req.ecuScope().isEmpty()) {
            throw new BusinessException(ApiError.E40001, "ecuScope 必填(R2 约束,单 ECU 场景填 [ecuName])");
        }
        if (req.txId() == null || req.rxId() == null) {
            throw new BusinessException(ApiError.E40001, "txId / rxId 必填");
        }
    }

    /** POST /api/channel/open 请求体(REST §4.1)。 */
    public record ChannelOpenRequest(
            String vin,
            String ecuName,
            List<String> ecuScope,
            String transport,
            String txId,
            String rxId,
            Integer globalTimeoutMs,
            Boolean force,
            String preemptPolicy) {}
}

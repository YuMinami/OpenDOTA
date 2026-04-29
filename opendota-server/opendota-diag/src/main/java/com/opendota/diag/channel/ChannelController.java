package com.opendota.diag.channel;

import com.opendota.common.envelope.Operator;
import com.opendota.common.payload.common.DoipConfig;
import com.opendota.common.payload.common.Transport;
import com.opendota.diag.api.OperatorContextResolver;
import com.opendota.diag.channel.ChannelCloseService.ChannelCloseResult;
import com.opendota.diag.channel.ChannelOpenService.ChannelOpenCommand;
import com.opendota.diag.channel.ChannelOpenService.ChannelOpenResult;
import com.opendota.common.web.ApiError;
import com.opendota.common.web.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断通道生命周期端点(REST §4.1 / §4.2)。
 *
 * <pre>
 *   POST /api/channel/open                  → 开通道  → {msgId, channelId}
 *   POST /api/channel/{channelId}/close     → 关通道  → {msgId, channelId, vin}
 * </pre>
 *
 * <p>本控制器只做参数校验 + Operator 注入,真正的"构 envelope + 投 MQTT + 维护本地登记"由
 * {@link ChannelOpenService} / {@link ChannelCloseService} 承担。全异步:实际"通道已开"通过
 * SSE {@code channel-event} 推送(Step 2.4 接入)。
 */
@RestController
@RequestMapping("/api/channel")
public class ChannelController {

    private final ChannelOpenService openService;
    private final ChannelCloseService closeService;
    private final OperatorContextResolver operatorResolver;

    public ChannelController(ChannelOpenService openService,
                             ChannelCloseService closeService,
                             OperatorContextResolver operatorResolver) {
        this.openService = openService;
        this.closeService = closeService;
        this.operatorResolver = operatorResolver;
    }

    @PostMapping("/open")
    public Map<String, String> open(@RequestBody ChannelOpenRequest req, HttpServletRequest http) {
        validateOpen(req);
        Operator operator = operatorResolver.resolve(http);
        ChannelOpenResult result = openService.open(new ChannelOpenCommand(
                req.vin(),
                req.ecuName(),
                req.ecuScope(),
                Transport.of(req.transport()),
                req.txId(),
                req.rxId(),
                req.doipConfig(),
                req.globalTimeoutMs(),
                req.force(),
                req.preemptPolicy(),
                operator));

        Map<String, String> body = new LinkedHashMap<>();
        body.put("channelId", result.channelId());
        body.put("msgId", result.msgId());
        return body;
    }

    @PostMapping("/{channelId}/close")
    public Map<String, String> close(@PathVariable String channelId,
                                     @RequestBody(required = false) ChannelCloseRequest req,
                                     HttpServletRequest http) {
        Boolean resetSession = req == null ? null : req.resetSession();
        Operator operator = operatorResolver.resolve(http);
        ChannelCloseResult result = closeService.close(channelId, resetSession, operator);

        Map<String, String> body = new LinkedHashMap<>();
        body.put("channelId", result.channelId());
        body.put("vin", result.vin());
        body.put("msgId", result.msgId());
        return body;
    }

    private static void validateOpen(ChannelOpenRequest req) {
        if (req == null) throw new BusinessException(ApiError.E40001, "请求 body 必填");
        if (req.vin() == null || req.vin().length() != 17) {
            throw new BusinessException(ApiError.E40001, "vin 必须为 17 位");
        }
        if (req.ecuName() == null || req.ecuName().isBlank()) {
            throw new BusinessException(ApiError.E40001, "ecuName 必填");
        }
        // 协议 §4.2 R2 + 符合性用例 B-1:ecuScope 缺失或为空必须直接拒绝,否则车端会回
        // channel_event { rejected, reason: ECU_SCOPE_REQUIRED },白白消耗一次 MQTT 往返。
        if (req.ecuScope() == null || req.ecuScope().isEmpty()) {
            throw new BusinessException(ApiError.E41002,
                    "ecuScope 必填(单 ECU 场景填 [ecuName],跨网关读由 GET /vehicle/{vin}/ecu-scope 推导)");
        }
        if (req.txId() == null || req.txId().isBlank()
                || req.rxId() == null || req.rxId().isBlank()) {
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
            DoipConfig doipConfig,
            Integer globalTimeoutMs,
            Boolean force,
            String preemptPolicy) {}

    /** POST /api/channel/{channelId}/close 请求体(REST §4.2)。 */
    public record ChannelCloseRequest(Boolean resetSession) {}
}

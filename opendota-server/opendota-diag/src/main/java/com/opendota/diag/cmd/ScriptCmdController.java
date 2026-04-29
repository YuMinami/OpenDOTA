package com.opendota.diag.cmd;

import com.opendota.common.envelope.Operator;
import com.opendota.common.payload.common.EcuBlock;
import com.opendota.diag.api.OperatorContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 多 ECU 脚本诊断入口(REST §5, Step 3.3)。
 *
 * <p>契约:
 * <pre>
 *   POST /api/cmd/script
 *   Headers:
 *     X-Operator-Id, X-Operator-Role, X-Tenant-Id, X-Ticket-Id
 *   Body:
 *     { vin, scriptId, executionMode, globalTimeoutMs, priority, ecus }
 *   Resp:
 *     { code:0, data:{ msgId:"..." } }
 * </pre>
 *
 * 全异步:HTTP 线程只负责"受理请求 + 记录 pending + MQTT publish",结果由 SSE {@code script-result} 推送。
 */
@RestController
@RequestMapping("/api/cmd")
public class ScriptCmdController {

    private final ScriptCmdService scriptCmdService;
    private final OperatorContextResolver operatorResolver;

    public ScriptCmdController(ScriptCmdService scriptCmdService, OperatorContextResolver operatorResolver) {
        this.scriptCmdService = scriptCmdService;
        this.operatorResolver = operatorResolver;
    }

    @PostMapping("/script")
    public Map<String, String> script(@RequestBody ScriptCmdRequest req,
                                      HttpServletRequest http) {
        Operator operator = operatorResolver.resolve(http);
        String msgId = scriptCmdService.dispatch(req, operator);
        return Map.of("msgId", msgId);
    }

    /**
     * POST /api/cmd/script 请求体(REST §5)。
     *
     * @param vin             目标车辆 VIN(17 位)
     * @param scriptId        脚本全局唯一 ID
     * @param executionMode   执行模式:parallel / sequential
     * @param globalTimeoutMs 全局超时(毫秒)
     * @param priority        优先级(0-9,0 最高)
     * @param ecus            ECU 执行块列表
     */
    public record ScriptCmdRequest(
            String vin,
            String scriptId,
            String executionMode,
            Integer globalTimeoutMs,
            Integer priority,
            List<EcuBlock> ecus) {}
}

package com.opendota.diag.cmd;

import com.opendota.common.envelope.Operator;
import com.opendota.common.payload.common.Step;
import com.opendota.common.payload.common.Transport;
import com.opendota.diag.api.OperatorContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 批量诊断指令入口(REST §4.4, Step 3.1)。
 *
 * <p>契约:
 * <pre>
 *   POST /api/cmd/batch
 *   Headers:
 *     X-Operator-Id, X-Operator-Role, X-Tenant-Id, X-Ticket-Id
 *   Body:
 *     { vin, ecuName, ecuScope, transport, txId, rxId, strategy, steps }
 *   Resp:
 *     { code:0, data:{ msgId:"..." } }
 * </pre>
 *
 * 全异步:HTTP 线程只负责"受理请求 + 记录 pending + MQTT publish",结果由 SSE {@code batch-result} 推送。
 */
@RestController
@RequestMapping("/api/cmd")
public class BatchCmdController {

    private final BatchCmdService batchCmdService;
    private final OperatorContextResolver operatorResolver;

    public BatchCmdController(BatchCmdService batchCmdService, OperatorContextResolver operatorResolver) {
        this.batchCmdService = batchCmdService;
        this.operatorResolver = operatorResolver;
    }

    @PostMapping("/batch")
    public Map<String, String> batch(@RequestBody BatchCmdRequest req,
                                     HttpServletRequest http) {
        Operator operator = operatorResolver.resolve(http);
        String msgId = batchCmdService.dispatch(req, operator);
        return Map.of("msgId", msgId);
    }

    /**
     * POST /api/cmd/batch 请求体(REST §4.4)。
     *
     * @param vin       目标车辆 VIN(17 位)
     * @param ecuName   目标 ECU 名称
     * @param ecuScope  ECU 作用域(v1.2 R2 必填)
     * @param transport 传输层类型,默认 UDS_ON_CAN
     * @param txId      CAN 发送 ID
     * @param rxId      CAN 接收 ID
     * @param strategy  错误策略:0=遇错中止,1=跳过继续
     * @param steps     步骤列表
     */
    public record BatchCmdRequest(
            String vin,
            String ecuName,
            List<String> ecuScope,
            Transport transport,
            String txId,
            String rxId,
            Integer strategy,
            List<Step> steps) {}
}

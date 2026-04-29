package com.opendota.diag.cmd;

import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.Operator;
import com.opendota.common.logging.DiagMdcKeys;
import com.opendota.common.payload.common.Step;
import com.opendota.common.payload.common.Transport;
import com.opendota.diag.dispatch.DiagDispatcher;
import com.opendota.common.web.ApiError;
import com.opendota.common.web.BusinessException;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量诊断下发服务(Step 3.1)。
 *
 * <p>顺序固定为:
 * <ol>
 *   <li>校验请求(ecuScope 必填)</li>
 *   <li>构造 C2V batch_cmd envelope</li>
 *   <li>写 pending {@code diag_record}</li>
 *   <li>发 MQTT;若失败则回滚本次 pending 记录</li>
 * </ol>
 */
@Service
public class BatchCmdService {

    private final DiagDispatcher dispatcher;
    private final DiagRecordRepository diagRecordRepository;
    private final DiagCmdMetrics metrics;

    public BatchCmdService(DiagDispatcher dispatcher, DiagRecordRepository diagRecordRepository,
                           DiagCmdMetrics metrics) {
        this.dispatcher = dispatcher;
        this.diagRecordRepository = diagRecordRepository;
        this.metrics = metrics;
    }

    public String dispatch(BatchCmdController.BatchCmdRequest request, Operator operator) {
        validate(request);

        DiagDispatcher.PreparedBatchCmd prepared = dispatcher.prepareBatchCmd(
                request.vin(), request.ecuName(), request.ecuScope(),
                request.transport(), request.txId(), request.rxId(),
                request.strategy(), request.steps(), operator);

        // 构建请求上下文快照,存入 req_raw_hex 用于审计
        Map<String, Object> reqSnapshot = buildReqSnapshot(request);

        diagRecordRepository.insertPendingBatchCmd(new DiagRecordRepository.PendingBatchCmd(
                prepared.envelope().msgId(),
                operator.tenantId(),
                request.vin(),
                request.ecuName(),
                DiagAction.BATCH_CMD.wireName(),
                reqSnapshot,
                operator.id(),
                operator.role() == null ? null : operator.role().wireName(),
                operator.ticketId(),
                currentTraceId()));

        try {
            dispatcher.publishPreparedBatchCmd(prepared);
            metrics.recordBatchDispatch(prepared.envelope().msgId());
            return prepared.envelope().msgId();
        } catch (RuntimeException ex) {
            diagRecordRepository.deleteByMsgId(prepared.envelope().msgId());
            throw ex;
        }
    }

    private static void validate(BatchCmdController.BatchCmdRequest req) {
        if (req == null) {
            throw new BusinessException(ApiError.E40001, "请求 body 必填");
        }
        if (req.vin() == null || req.vin().isBlank()) {
            throw new BusinessException(ApiError.E40001, "vin 必填");
        }
        if (req.vin().length() != 17) {
            throw new BusinessException(ApiError.E40002, "vin 必须为 17 位");
        }
        if (req.ecuName() == null || req.ecuName().isBlank()) {
            throw new BusinessException(ApiError.E40001, "ecuName 必填");
        }
        if (req.ecuScope() == null || req.ecuScope().isEmpty()) {
            throw new BusinessException(ApiError.E41002, "ecuScope 必填(协议 v1.2 R2)");
        }
        if (req.steps() == null || req.steps().isEmpty()) {
            throw new BusinessException(ApiError.E40001, "steps 必填且不能为空");
        }
    }

    private static Map<String, Object> buildReqSnapshot(BatchCmdController.BatchCmdRequest req) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("vin", req.vin());
        snapshot.put("ecuName", req.ecuName());
        snapshot.put("ecuScope", req.ecuScope());
        snapshot.put("transport", req.transport() == null ? Transport.UDS_ON_CAN.wireName() : req.transport().wireName());
        snapshot.put("txId", req.txId());
        snapshot.put("rxId", req.rxId());
        snapshot.put("strategy", req.strategy());
        snapshot.put("stepCount", req.steps() == null ? 0 : req.steps().size());
        snapshot.put("steps", req.steps());
        return snapshot;
    }

    private static String currentTraceId() {
        String traceId = MDC.get(DiagMdcKeys.TRACE_ID);
        return DiagMdcKeys.NO_VALUE.equals(traceId) ? null : traceId;
    }
}

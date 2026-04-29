package com.opendota.diag.cmd;

import com.fasterxml.jackson.databind.JsonNode;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.Operator;
import com.opendota.common.logging.DiagMdcKeys;
import com.opendota.common.payload.common.MacroType;
import com.opendota.common.payload.common.Step;
import com.opendota.common.payload.common.Transport;
import com.opendota.diag.arbitration.EcuLockRegistry;
import com.opendota.diag.dispatch.DiagDispatcher;
import com.opendota.common.web.ApiError;
import com.opendota.common.web.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static final Logger log = LoggerFactory.getLogger(BatchCmdService.class);

    private final DiagDispatcher dispatcher;
    private final DiagRecordRepository diagRecordRepository;
    private final DiagCmdMetrics metrics;
    private final EcuLockRegistry ecuLockRegistry;

    public BatchCmdService(DiagDispatcher dispatcher, DiagRecordRepository diagRecordRepository,
                           DiagCmdMetrics metrics, EcuLockRegistry ecuLockRegistry) {
        this.dispatcher = dispatcher;
        this.diagRecordRepository = diagRecordRepository;
        this.metrics = metrics;
        this.ecuLockRegistry = ecuLockRegistry;
    }

    public String dispatch(BatchCmdController.BatchCmdRequest request, Operator operator) {
        validate(request);

        // 宏步骤审计日志(协议 §7):下发时记录 macroType
        List<String> macroTypes = extractMacroTypes(request.steps());
        if (!macroTypes.isEmpty()) {
            log.info("batch_cmd 含宏步骤 macroTypes={} vin={} ecuName={}",
                    macroTypes, request.vin(), request.ecuName());
        }

        // 协议 §10.2.3:按 ecuScope 字典序原子获取云端 ECU 锁
        List<EcuLockRegistry.AcquiredLock> acquiredLocks =
                ecuLockRegistry.tryAcquireAll(request.vin(), request.ecuScope());
        try {
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
                if (!macroTypes.isEmpty()) {
                    metrics.recordMacroDispatch(macroTypes);
                }
                return prepared.envelope().msgId();
            } catch (RuntimeException ex) {
                diagRecordRepository.deleteByMsgId(prepared.envelope().msgId());
                throw ex;
            }
        } finally {
            // batch_cmd 是单次下发,完成后立即释放锁
            ecuLockRegistry.releaseAll(acquiredLocks);
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
        // 宏步骤必填参数校验(协议 §7)
        validateMacroSteps(req.steps());
    }

    /**
     * 校验宏步骤的必填参数(协议 §7)。
     *
     * <p>云端只校验参数完整性,不处理宏执行细节(宏逻辑在车端)。
     */
    private static void validateMacroSteps(List<Step> steps) {
        for (Step step : steps) {
            if (step.type() == null || !MacroType.isMacro(step.type())) {
                continue;
            }
            MacroType macroType = MacroType.fromWireName(step.type());
            if (macroType == null) {
                continue;
            }
            JsonNode params = step.macroParams();
            for (String required : macroType.requiredParams()) {
                if (params == null || params.isNull() || !params.has(required)
                        || params.get(required).isNull()
                        || (params.get(required).isTextual() && params.get(required).asText().isBlank())) {
                    throw new BusinessException(ApiError.E40001,
                            "宏步骤 seqId=" + step.seqId() + " type=" + step.type()
                                    + " 缺少必填参数: macroParams." + required);
                }
            }
        }
    }

    /**
     * 从步骤列表中提取所有宏类型(去重),用于审计日志。
     */
    static List<String> extractMacroTypes(List<Step> steps) {
        if (steps == null) {
            return List.of();
        }
        Set<String> macroTypes = new LinkedHashSet<>();
        for (Step step : steps) {
            if (step.type() != null && MacroType.isMacro(step.type())) {
                macroTypes.add(step.type());
            }
        }
        return List.copyOf(macroTypes);
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
        // 宏类型审计追踪(协议 §7)
        List<String> macroTypes = extractMacroTypes(req.steps());
        if (!macroTypes.isEmpty()) {
            snapshot.put("macroTypes", macroTypes);
        }
        snapshot.put("steps", req.steps());
        return snapshot;
    }

    private static String currentTraceId() {
        String traceId = MDC.get(DiagMdcKeys.TRACE_ID);
        return DiagMdcKeys.NO_VALUE.equals(traceId) ? null : traceId;
    }
}

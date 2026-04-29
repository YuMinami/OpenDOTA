package com.opendota.diag.cmd;

import com.fasterxml.jackson.databind.JsonNode;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.Operator;
import com.opendota.common.logging.DiagMdcKeys;
import com.opendota.common.payload.common.EcuBlock;
import com.opendota.common.payload.common.EcuScope;
import com.opendota.common.payload.common.MacroType;
import com.opendota.common.payload.common.Step;
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
import java.util.stream.Collectors;

/**
 * 多 ECU 脚本下发服务(Step 3.3)。
 *
 * <p>顺序固定为:
 * <ol>
 *   <li>校验请求(vin / executionMode / ecus 必填)</li>
 *   <li>聚合所有 ECU 的 ecuScope,字典序去重(协议 §10.2.3)</li>
 *   <li>原子获取云端 ECU 锁</li>
 *   <li>构造 C2V script_cmd envelope</li>
 *   <li>写 pending {@code diag_record}</li>
 *   <li>发 MQTT;若失败则回滚本次 pending 记录</li>
 *   <li>释放锁</li>
 * </ol>
 */
@Service
public class ScriptCmdService {

    private static final Logger log = LoggerFactory.getLogger(ScriptCmdService.class);

    private final DiagDispatcher dispatcher;
    private final DiagRecordRepository diagRecordRepository;
    private final DiagCmdMetrics metrics;
    private final EcuLockRegistry ecuLockRegistry;

    public ScriptCmdService(DiagDispatcher dispatcher, DiagRecordRepository diagRecordRepository,
                            DiagCmdMetrics metrics, EcuLockRegistry ecuLockRegistry) {
        this.dispatcher = dispatcher;
        this.diagRecordRepository = diagRecordRepository;
        this.metrics = metrics;
        this.ecuLockRegistry = ecuLockRegistry;
    }

    public String dispatch(ScriptCmdController.ScriptCmdRequest request, Operator operator) {
        validate(request);

        // 宏步骤审计日志(协议 §7):下发时记录 macroType
        List<String> macroTypes = extractAllMacroTypes(request.ecus());
        if (!macroTypes.isEmpty()) {
            log.info("script_cmd 含宏步骤 macroTypes={} vin={}", macroTypes, request.vin());
        }

        // 聚合所有 ECU 的 ecuScope,去重字典序排序(协议 §10.2.3)
        List<String> aggregatedScope = aggregateEcuScope(request.ecus());
        String ecuNameJoined = request.ecus().stream()
                .map(EcuBlock::ecuName)
                .collect(Collectors.joining(","));

        // 协议 §10.2.3:按 ecuScope 字典序原子获取云端 ECU 锁
        List<EcuLockRegistry.AcquiredLock> acquiredLocks =
                ecuLockRegistry.tryAcquireAll(request.vin(), aggregatedScope);
        try {
            DiagDispatcher.PreparedScriptCmd prepared = dispatcher.prepareScriptCmd(
                    request.vin(), request.scriptId(), request.executionMode(),
                    request.globalTimeoutMs(), request.priority(),
                    request.ecus(), operator);

            // 构建请求上下文快照,存入 req_raw_hex 用于审计
            Map<String, Object> reqSnapshot = buildReqSnapshot(request);

            diagRecordRepository.insertPendingScriptCmd(new DiagRecordRepository.PendingScriptCmd(
                    prepared.envelope().msgId(),
                    operator.tenantId(),
                    request.vin(),
                    ecuNameJoined,
                    DiagAction.SCRIPT_CMD.wireName(),
                    reqSnapshot,
                    operator.id(),
                    operator.role() == null ? null : operator.role().wireName(),
                    operator.ticketId(),
                    currentTraceId()));

            try {
                dispatcher.publishPreparedScriptCmd(prepared);
                metrics.recordScriptDispatch(prepared.envelope().msgId());
                if (!macroTypes.isEmpty()) {
                    metrics.recordMacroDispatch(macroTypes);
                }
                return prepared.envelope().msgId();
            } catch (RuntimeException ex) {
                diagRecordRepository.deleteByMsgId(prepared.envelope().msgId());
                throw ex;
            }
        } finally {
            // script_cmd 是单次下发,完成后立即释放锁
            ecuLockRegistry.releaseAll(acquiredLocks);
        }
    }

    private static void validate(ScriptCmdController.ScriptCmdRequest req) {
        if (req == null) {
            throw new BusinessException(ApiError.E40001, "请求 body 必填");
        }
        if (req.vin() == null || req.vin().isBlank()) {
            throw new BusinessException(ApiError.E40001, "vin 必填");
        }
        if (req.vin().length() != 17) {
            throw new BusinessException(ApiError.E40002, "vin 必须为 17 位");
        }
        if (req.scriptId() == null || req.scriptId().isBlank()) {
            throw new BusinessException(ApiError.E40001, "scriptId 必填");
        }
        if (req.executionMode() == null || req.executionMode().isBlank()) {
            throw new BusinessException(ApiError.E40001, "executionMode 必填");
        }
        if (!"parallel".equals(req.executionMode()) && !"sequential".equals(req.executionMode())) {
            throw new BusinessException(ApiError.E40002, "executionMode 必须为 parallel 或 sequential");
        }
        if (req.ecus() == null || req.ecus().isEmpty()) {
            throw new BusinessException(ApiError.E40001, "ecus 必填且不能为空");
        }
        for (int i = 0; i < req.ecus().size(); i++) {
            EcuBlock ecu = req.ecus().get(i);
            if (ecu.ecuName() == null || ecu.ecuName().isBlank()) {
                throw new BusinessException(ApiError.E40001, "ecus[" + i + "].ecuName 必填");
            }
            if (ecu.ecuScope() == null || ecu.ecuScope().isEmpty()) {
                throw new BusinessException(ApiError.E41002, "ecus[" + i + "].ecuScope 必填(协议 v1.2 R2)");
            }
            if (ecu.steps() == null || ecu.steps().isEmpty()) {
                throw new BusinessException(ApiError.E40001, "ecus[" + i + "].steps 必填且不能为空");
            }
            // 宏步骤必填参数校验(协议 §7)
            validateMacroSteps(ecu.steps(), "ecus[" + i + "].");
        }
    }

    /**
     * 校验宏步骤的必填参数(协议 §7)。
     */
    private static void validateMacroSteps(List<Step> steps, String pathPrefix) {
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
                            pathPrefix + "步骤 seqId=" + step.seqId() + " type=" + step.type()
                                    + " 缺少必填参数: macroParams." + required);
                }
            }
        }
    }

    /**
     * 聚合所有 ECU 块中步骤的宏类型(去重),用于审计日志。
     */
    static List<String> extractAllMacroTypes(List<EcuBlock> ecus) {
        if (ecus == null) {
            return List.of();
        }
        Set<String> macroTypes = new LinkedHashSet<>();
        for (EcuBlock ecu : ecus) {
            if (ecu.steps() == null) {
                continue;
            }
            for (Step step : ecu.steps()) {
                if (step.type() != null && MacroType.isMacro(step.type())) {
                    macroTypes.add(step.type());
                }
            }
        }
        return List.copyOf(macroTypes);
    }

    /**
     * 聚合所有 ECU 块的 ecuScope,去重后字典序排序。
     */
    static List<String> aggregateEcuScope(List<EcuBlock> ecus) {
        return ecus.stream()
                .flatMap(ecu -> ecu.ecuScope().stream())
                .distinct()
                .sorted()
                .toList();
    }

    private static Map<String, Object> buildReqSnapshot(ScriptCmdController.ScriptCmdRequest req) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("vin", req.vin());
        snapshot.put("scriptId", req.scriptId());
        snapshot.put("executionMode", req.executionMode());
        snapshot.put("globalTimeoutMs", req.globalTimeoutMs());
        snapshot.put("priority", req.priority());
        snapshot.put("ecuCount", req.ecus() == null ? 0 : req.ecus().size());
        // 宏类型审计追踪(协议 §7)
        List<String> macroTypes = extractAllMacroTypes(req.ecus());
        if (!macroTypes.isEmpty()) {
            snapshot.put("macroTypes", macroTypes);
        }
        snapshot.put("ecus", req.ecus());
        return snapshot;
    }

    private static String currentTraceId() {
        String traceId = MDC.get(DiagMdcKeys.TRACE_ID);
        return DiagMdcKeys.NO_VALUE.equals(traceId) ? null : traceId;
    }
}

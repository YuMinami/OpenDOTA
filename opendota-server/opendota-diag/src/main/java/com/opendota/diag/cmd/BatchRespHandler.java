package com.opendota.diag.cmd;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.Operator;
import com.opendota.common.logging.DiagMdcKeys;
import com.opendota.common.payload.batch.BatchRespPayload;
import com.opendota.common.payload.batch.BatchStepResult;
import com.opendota.mqtt.subscriber.V2CHandler;
import com.opendota.odx.service.OdxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 批量响应处理器(Step 3.1)。
 *
 * <p>职责:
 * <ol>
 *   <li>用 {@code msgId} 关联 pending {@code diag_record}</li>
 *   <li>逐条执行 ODX 翻译(raw_uds 步骤)</li>
 *   <li>聚合结果,更新 {@code diag_record}</li>
 *   <li>写入 {@code sse_event} 类型 {@code batch-result}</li>
 *   <li>事务提交后向 Redis 广播 {@code dota:resp:{vin}}</li>
 * </ol>
 */
@Component
public class BatchRespHandler implements V2CHandler {

    private static final Logger log = LoggerFactory.getLogger(BatchRespHandler.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};

    private final OdxService odxService;
    private final DiagRecordRepository diagRecordRepository;
    private final SseEventRepository sseEventRepository;
    private final SingleRespRedisPublisher redisPublisher;
    private final DiagCmdMetrics metrics;
    private final ObjectMapper objectMapper;

    public BatchRespHandler(OdxService odxService,
                            DiagRecordRepository diagRecordRepository,
                            SseEventRepository sseEventRepository,
                            SingleRespRedisPublisher redisPublisher,
                            DiagCmdMetrics metrics,
                            ObjectMapper objectMapper) {
        this.odxService = odxService;
        this.diagRecordRepository = diagRecordRepository;
        this.sseEventRepository = sseEventRepository;
        this.redisPublisher = redisPublisher;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(DiagAction act) {
        return act == DiagAction.BATCH_RESP;
    }

    @Override
    @Transactional
    public void handle(String topic, DiagMessage<?> envelope) {
        if (!(envelope.payload() instanceof BatchRespPayload payload)) {
            log.warn("忽略非 BatchRespPayload batch_resp topic={} payloadType={}",
                    topic, envelope.payload() == null ? "null" : envelope.payload().getClass().getName());
            return;
        }

        String msgId = envelope.msgId();
        DiagContext context = resolveContext(msgId, payload, envelope);
        List<Map<String, Object>> translatedSteps = safeTranslateAll(context, payload);
        Instant respondedAt = Instant.now();

        long diagRecordId = diagRecordRepository.upsertBatchResponse(new DiagRecordRepository.CompletedBatchCmd(
                msgId,
                context.tenantId(),
                context.vin(),
                context.ecuName(),
                DiagAction.BATCH_CMD.wireName(),
                context.reqRawHex(),
                buildResultsMap(payload),
                payload.overallStatus(),
                resolveOverallErrorCode(payload),
                context.operatorId(),
                context.operatorRole(),
                context.ticketId(),
                context.traceId(),
                respondedAt,
                buildTranslatedMap(payload, translatedSteps)));

        Map<String, Object> summary = buildPayloadSummary(context, payload, translatedSteps, respondedAt, msgId);
        long sseEventId = sseEventRepository.insertBatchResult(context.tenantId(), context.vin(), diagRecordId, summary);
        metrics.recordBatchResponse(msgId, payload.overallStatus() == 0);
        publishAfterCommit(context.vin(), sseEventId, summary);

        log.info("batch_resp 已处理 topic={} msgId={} vin={} overallStatus={} stepCount={}",
                topic, msgId, context.vin(), payload.overallStatus(),
                payload.results() == null ? 0 : payload.results().size());
    }

    private DiagContext resolveContext(String msgId, BatchRespPayload payload, DiagMessage<?> envelope) {
        Optional<DiagRecordRepository.DiagRecordContext> existing = diagRecordRepository.findByMsgId(msgId);
        if (existing.isPresent()) {
            DiagRecordRepository.DiagRecordContext record = existing.get();
            return new DiagContext(
                    record.tenantId(),
                    record.vin(),
                    record.ecuName(),
                    record.reqRawHex(),
                    record.operatorId(),
                    record.operatorRole(),
                    record.ticketId(),
                    record.traceId());
        }

        // fallback:从 envelope 推断上下文
        Operator operator = envelope.operator();
        return new DiagContext(
                operator == null || blankToNull(operator.tenantId()) == null
                        ? DiagMdcKeys.DEFAULT_TENANT_ID
                        : operator.tenantId(),
                envelope.vin(),
                null,
                null,
                operator == null ? DiagMdcKeys.SYSTEM_OPERATOR_ID : operator.id(),
                operator == null || operator.role() == null ? null : operator.role().wireName(),
                operator == null ? null : operator.ticketId(),
                currentTraceId());
    }

    /**
     * 对批量结果逐条执行 ODX 翻译。
     *
     * <p>仅对 {@code raw_uds} 类型且有请求上下文的步骤执行翻译;
     * 宏类型(macro_*)和缺少上下文的步骤走 fallback。
     */
    private List<Map<String, Object>> safeTranslateAll(DiagContext context, BatchRespPayload payload) {
        if (payload.results() == null) {
            return List.of();
        }

        List<Map<String, Object>> reqSteps = parseReqSteps(context.reqRawHex());
        List<Map<String, Object>> translated = new ArrayList<>();

        for (BatchStepResult stepResult : payload.results()) {
            Map<String, Object> stepTranslated = safeTranslateSingle(context, stepResult, reqSteps);
            translated.add(stepTranslated);
        }
        return translated;
    }

    private Map<String, Object> safeTranslateSingle(DiagContext context,
                                                     BatchStepResult stepResult,
                                                     List<Map<String, Object>> reqSteps) {
        String stepType = resolveStepType(stepResult.seqId(), reqSteps);
        String reqData = resolveStepReqData(stepResult.seqId(), reqSteps);

        // 宏类型不需要 ODX 翻译
        if (stepType != null && stepType.startsWith("macro_")) {
            return buildMacroTranslation(stepResult, stepType);
        }

        // raw_uds:尝试 ODX 翻译
        if (context.ecuName() != null && blankToNull(reqData) != null && blankToNull(stepResult.resData()) != null) {
            try {
                return odxService.translateSingleResponse(
                        context.vin(),
                        context.ecuName(),
                        reqData,
                        stepResult.resData());
            } catch (Exception ex) {
                log.warn("ODX 翻译失败 seqId={} vin={} ecu={} req={} res={}",
                        stepResult.seqId(), context.vin(), context.ecuName(), reqData, stepResult.resData(), ex);
            }
        }

        return fallbackTranslation(stepResult, "缺少请求上下文或翻译失败,已回退为原始响应展示");
    }

    private Map<String, Object> buildMacroTranslation(BatchStepResult stepResult, String macroType) {
        Map<String, Object> translated = new LinkedHashMap<>();
        translated.put("translationType", "macro");
        translated.put("macroType", macroType);
        translated.put("status", stepResult.status());
        translated.put("msg", blankToNull(stepResult.msg()));
        translated.put("rawResponse", normalizeHex(stepResult.resData()));
        translated.put("errorCode", blankToNull(stepResult.errorCode()));
        translated.put("summaryText", stepResult.msg() != null ? stepResult.msg() : macroType + " 执行完成");
        return translated;
    }

    private Map<String, Object> fallbackTranslation(BatchStepResult stepResult, String reason) {
        Map<String, Object> translated = new LinkedHashMap<>();
        translated.put("translationType", blankToNull(stepResult.resData()) == null ? "empty" : "raw");
        translated.put("rawResponse", normalizeHex(stepResult.resData()));
        translated.put("errorCode", blankToNull(stepResult.errorCode()));
        translated.put("summaryText", reason);
        return translated;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseReqSteps(String reqRawHex) {
        if (blankToNull(reqRawHex) == null) {
            return List.of();
        }
        try {
            Map<String, Object> snapshot = objectMapper.readValue(reqRawHex, MAP_TYPE);
            Object stepsObj = snapshot.get("steps");
            if (stepsObj instanceof List<?> stepsList) {
                // 将每个 Step 对象转为 Map
                return stepsList.stream()
                        .map(item -> objectMapper.convertValue(item, MAP_TYPE))
                        .toList();
            }
            return List.of();
        } catch (Exception e) {
            log.warn("解析 batch 请求上下文失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String resolveStepType(Integer seqId, List<Map<String, Object>> reqSteps) {
        if (seqId == null || reqSteps.isEmpty()) {
            return null;
        }
        return reqSteps.stream()
                .filter(s -> seqId.equals(s.get("seqId")))
                .map(s -> (String) s.get("type"))
                .findFirst()
                .orElse(null);
    }

    private String resolveStepReqData(Integer seqId, List<Map<String, Object>> reqSteps) {
        if (seqId == null || reqSteps.isEmpty()) {
            return null;
        }
        return reqSteps.stream()
                .filter(s -> seqId.equals(s.get("seqId")))
                .map(s -> (String) s.get("data"))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> buildResultsMap(BatchRespPayload payload) {
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("overallStatus", payload.overallStatus());
        results.put("taskDuration", payload.taskDuration());
        results.put("steps", payload.results());
        return results;
    }

    private Map<String, Object> buildTranslatedMap(BatchRespPayload payload, List<Map<String, Object>> translatedSteps) {
        Map<String, Object> translated = new LinkedHashMap<>();
        translated.put("overallStatus", payload.overallStatus());
        translated.put("taskDuration", payload.taskDuration());
        translated.put("steps", translatedSteps);
        return translated;
    }

    private String resolveOverallErrorCode(BatchRespPayload payload) {
        if (payload.overallStatus() == null || payload.overallStatus() == 0) {
            return null;
        }
        if (payload.results() == null) {
            return null;
        }
        // 取第一个失败步骤的 errorCode
        return payload.results().stream()
                .filter(r -> r.status() != null && r.status() != 0)
                .map(BatchStepResult::errorCode)
                .findFirst()
                .orElse("BATCH_PARTIAL");
    }

    private Map<String, Object> buildPayloadSummary(DiagContext context,
                                                     BatchRespPayload payload,
                                                     List<Map<String, Object>> translatedSteps,
                                                     Instant respondedAt,
                                                     String msgId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("msgId", msgId);
        summary.put("vin", context.vin());
        summary.put("ecuName", context.ecuName());
        summary.put("overallStatus", payload.overallStatus());
        summary.put("taskDuration", payload.taskDuration());
        summary.put("stepCount", payload.results() == null ? 0 : payload.results().size());
        summary.put("steps", translatedSteps);
        summary.put("respondedAt", respondedAt.toEpochMilli());
        return summary;
    }

    private static String normalizeHex(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace("0x", "")
                .replace("0X", "")
                .replaceAll("\\s+", "")
                .trim()
                .toUpperCase();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String currentTraceId() {
        String traceId = MDC.get(DiagMdcKeys.TRACE_ID);
        return DiagMdcKeys.NO_VALUE.equals(traceId) ? null : traceId;
    }

    private void publishAfterCommit(String vin, long sseEventId, Map<String, Object> summary) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            redisPublisher.publishBatchResult(vin, sseEventId, summary);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisPublisher.publishBatchResult(vin, sseEventId, summary);
            }
        });
    }

    private record DiagContext(
            String tenantId,
            String vin,
            String ecuName,
            String reqRawHex,
            String operatorId,
            String operatorRole,
            String ticketId,
            String traceId) {
    }
}

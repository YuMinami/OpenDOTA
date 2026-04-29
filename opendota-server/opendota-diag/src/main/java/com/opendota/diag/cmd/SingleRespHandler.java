package com.opendota.diag.cmd;

import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.envelope.Operator;
import com.opendota.common.logging.DiagMdcKeys;
import com.opendota.common.payload.single.SingleRespPayload;
import com.opendota.diag.channel.ChannelContext;
import com.opendota.diag.channel.ChannelManager;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 单步响应处理器。
 *
 * <p>职责:
 * <ol>
 *   <li>用 {@code cmdId} 关联 pending {@code diag_record}</li>
 *   <li>执行 ODX 翻译</li>
 *   <li>更新 {@code diag_record} 并写入 {@code sse_event}</li>
 *   <li>事务提交后向 Redis 广播 {@code dota:resp:{vin}}</li>
 * </ol>
 */
@Component
public class SingleRespHandler implements V2CHandler {

    private static final Logger log = LoggerFactory.getLogger(SingleRespHandler.class);

    private final OdxService odxService;
    private final ChannelManager channelManager;
    private final DiagRecordRepository diagRecordRepository;
    private final SseEventRepository sseEventRepository;
    private final SingleRespRedisPublisher redisPublisher;
    private final DiagCmdMetrics metrics;

    public SingleRespHandler(OdxService odxService,
                             ChannelManager channelManager,
                             DiagRecordRepository diagRecordRepository,
                             SseEventRepository sseEventRepository,
                             SingleRespRedisPublisher redisPublisher,
                             DiagCmdMetrics metrics) {
        this.odxService = odxService;
        this.channelManager = channelManager;
        this.diagRecordRepository = diagRecordRepository;
        this.sseEventRepository = sseEventRepository;
        this.redisPublisher = redisPublisher;
        this.metrics = metrics;
    }

    @Override
    public boolean supports(DiagAction act) {
        return act == DiagAction.SINGLE_RESP;
    }

    @Override
    @Transactional
    public void handle(String topic, DiagMessage<?> envelope) {
        if (!(envelope.payload() instanceof SingleRespPayload payload)) {
            log.warn("忽略非 SingleRespPayload single_resp topic={} payloadType={}",
                    topic, envelope.payload() == null ? "null" : envelope.payload().getClass().getName());
            return;
        }

        String correlationMsgId = blankToNull(payload.cmdId()) != null ? payload.cmdId() : envelope.msgId();
        DiagContext context = resolveContext(correlationMsgId, payload, envelope);
        Map<String, Object> translated = safeTranslate(context, payload);
        Instant respondedAt = Instant.now();

        long diagRecordId = diagRecordRepository.upsertSingleResponse(new DiagRecordRepository.CompletedSingleCmd(
                correlationMsgId,
                context.tenantId(),
                context.vin(),
                context.ecuName(),
                DiagAction.SINGLE_CMD.wireName(),
                context.reqRawHex(),
                normalizeHex(payload.resData()),
                payload.status(),
                blankToNull(payload.errorCode()),
                context.operatorId(),
                context.operatorRole(),
                context.ticketId(),
                context.traceId(),
                respondedAt,
                translated));

        Map<String, Object> summary = buildPayloadSummary(context, payload, translated, respondedAt, correlationMsgId);
        long sseEventId = sseEventRepository.insertDiagResult(context.tenantId(), context.vin(), diagRecordId, summary);
        metrics.recordResponse(correlationMsgId, payload.status() == 0);
        publishAfterCommit(context.vin(), sseEventId, summary);

        channelManager.markIdle(payload.channelId());
        log.info("single_resp 已处理 topic={} msgId={} vin={} channelId={} status={}",
                topic, correlationMsgId, context.vin(), payload.channelId(), payload.status());
    }

    private DiagContext resolveContext(String msgId, SingleRespPayload payload, DiagMessage<?> envelope) {
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

        ChannelContext channel = payload.channelId() == null ? null : channelManager.get(payload.channelId()).orElse(null);
        Operator operator = envelope.operator() != null
                ? envelope.operator()
                : channel == null ? null : channel.getOperator();

        return new DiagContext(
                operator == null || blankToNull(operator.tenantId()) == null
                        ? DiagMdcKeys.DEFAULT_TENANT_ID
                        : operator.tenantId(),
                channel == null ? envelope.vin() : channel.getVin(),
                channel == null ? null : channel.getEcuName(),
                null,
                operator == null ? DiagMdcKeys.SYSTEM_OPERATOR_ID : operator.id(),
                operator == null || operator.role() == null ? null : operator.role().wireName(),
                operator == null ? null : operator.ticketId(),
                currentTraceId());
    }

    private Map<String, Object> safeTranslate(DiagContext context, SingleRespPayload payload) {
        try {
            if (context.ecuName() == null || blankToNull(context.reqRawHex()) == null) {
                return fallbackTranslation(payload, "缺少请求上下文,无法执行 ODX 翻译");
            }
            return odxService.translateSingleResponse(
                    context.vin(),
                    context.ecuName(),
                    context.reqRawHex(),
                    payload.resData());
        } catch (Exception ex) {
            log.warn("ODX 翻译失败 vin={} ecu={} req={} res={}",
                    context.vin(), context.ecuName(), context.reqRawHex(), payload.resData(), ex);
            return fallbackTranslation(payload, "ODX 翻译失败,已回退为原始响应展示");
        }
    }

    private Map<String, Object> fallbackTranslation(SingleRespPayload payload, String reason) {
        Map<String, Object> translated = new LinkedHashMap<>();
        translated.put("translationType", blankToNull(payload.resData()) == null ? "empty" : "raw");
        translated.put("rawResponse", normalizeHex(payload.resData()));
        translated.put("errorCode", blankToNull(payload.errorCode()));
        translated.put("summaryText", reason);
        return translated;
    }

    private Map<String, Object> buildPayloadSummary(DiagContext context,
                                                    SingleRespPayload payload,
                                                    Map<String, Object> translated,
                                                    Instant respondedAt,
                                                    String msgId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("msgId", msgId);
        summary.put("cmdId", payload.cmdId());
        summary.put("channelId", payload.channelId());
        summary.put("vin", context.vin());
        summary.put("ecuName", context.ecuName());
        summary.put("status", payload.status());
        summary.put("errorCode", blankToNull(payload.errorCode()));
        summary.put("resData", normalizeHex(payload.resData()));
        summary.put("execDuration", payload.execDuration());
        summary.put("translated", translated);
        summary.put("summaryText", translated.get("summaryText"));
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
            redisPublisher.publishDiagResult(vin, sseEventId, summary);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisPublisher.publishDiagResult(vin, sseEventId, summary);
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

package com.opendota.task.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.logging.DiagMdcKeys;
import com.opendota.mqtt.subscriber.V2CHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 周期/条件任务 execution_begin 处理器(协议 §8.5.1)。
 *
 * <p>车端每次触发周期/条件任务时,先发 {@code execution_begin},云端以此为权威计数。
 * 处理逻辑:
 * <ol>
 *   <li>INSERT task_execution_log (ON CONFLICT DO NOTHING 幂等)</li>
 *   <li>UPDATE task_dispatch_record.current_execution_count = GREATEST(old, seq)</li>
 * </ol>
 */
@Component
public class ExecutionBeginHandler implements V2CHandler {

    private static final Logger log = LoggerFactory.getLogger(ExecutionBeginHandler.class);

    private final TaskExecutionLogRepository execLogRepo;
    private final JdbcTemplate jdbc;
    private final ExecutionMetrics metrics;

    public ExecutionBeginHandler(TaskExecutionLogRepository execLogRepo,
                                  JdbcTemplate jdbc,
                                  ExecutionMetrics metrics) {
        this.execLogRepo = execLogRepo;
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Override
    public boolean supports(DiagAction act) {
        return act == DiagAction.EXECUTION_BEGIN;
    }

    @Override
    @Transactional
    public void handle(String topic, DiagMessage<?> envelope) {
        if (!(envelope.payload() instanceof JsonNode payload)) {
            log.warn("execution_begin payload 非 JsonNode topic={} payloadType={}",
                    topic, envelope.payload() == null ? "null" : envelope.payload().getClass().getName());
            return;
        }

        String taskId = extractText(payload, "taskId");
        Integer executionSeq = extractInt(payload, "executionSeq");
        Long triggerAt = extractLong(payload, "triggerAt");
        String triggerSource = extractText(payload, "triggerSource");

        if (taskId == null || executionSeq == null || triggerAt == null) {
            log.warn("execution_begin 缺少必填字段 topic={} taskId={} seq={} triggerAt={}",
                    topic, taskId, executionSeq, triggerAt);
            return;
        }

        String vin = envelope.vin();
        String msgId = envelope.msgId();
        String tenantId = resolveTenantId(envelope);

        // 1. INSERT ON CONFLICT DO NOTHING (幂等)
        int inserted = execLogRepo.insertBegin(tenantId, taskId, vin, executionSeq, triggerAt, msgId);

        // 2. UPDATE current_execution_count = GREATEST(old, seq)
        int updated = jdbc.update(
                """
                UPDATE task_dispatch_record
                SET current_execution_count = GREATEST(current_execution_count, ?),
                    last_reported_at = now()
                WHERE task_id = ? AND vin = ?
                """,
                executionSeq, taskId, vin);

        if (inserted > 0) {
            metrics.recordBegin(taskId, triggerSource);
            log.info("execution_begin 已处理 taskId={} vin={} seq={} triggerSource={} dispatchUpdated={}",
                    taskId, vin, executionSeq, triggerSource, updated);
        } else {
            log.debug("execution_begin 幂等跳过 taskId={} vin={} seq={}", taskId, vin, executionSeq);
        }
    }

    private String resolveTenantId(DiagMessage<?> envelope) {
        if (envelope.operator() != null && envelope.operator().tenantId() != null) {
            return envelope.operator().tenantId();
        }
        String mdcTenant = MDC.get(DiagMdcKeys.TENANT_ID);
        if (mdcTenant != null && !DiagMdcKeys.NO_VALUE.equals(mdcTenant)) {
            return mdcTenant;
        }
        return DiagMdcKeys.DEFAULT_TENANT_ID;
    }

    private static String extractText(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child != null && !child.isNull() ? child.asText() : null;
    }

    private static Integer extractInt(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child != null && !child.isNull() ? child.asInt() : null;
    }

    private static Long extractLong(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child != null && !child.isNull() ? child.asLong() : null;
    }
}

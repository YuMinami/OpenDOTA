package com.opendota.task.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.logging.DiagMdcKeys;
import com.opendota.mqtt.subscriber.V2CHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 周期/条件任务 execution_end 处理器(协议 §8.5.1)。
 *
 * <p>车端执行完成后发送 {@code execution_end},云端回填结果到 task_execution_log。
 * 处理逻辑:
 * <ol>
 *   <li>UPDATE task_execution_log SET end_msg_id, end_reported_at, overall_status, result_payload, execution_duration
 *       WHERE task_id + vin + execution_seq AND end_reported_at IS NULL</li>
 *   <li>若 affected=0,说明 begin 未到达(乱序)或已回填(重传),记 WARN</li>
 * </ol>
 */
@Component
public class ExecutionEndHandler implements V2CHandler {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEndHandler.class);

    private final TaskExecutionLogRepository execLogRepo;
    private final ExecutionMetrics metrics;

    public ExecutionEndHandler(TaskExecutionLogRepository execLogRepo, ExecutionMetrics metrics) {
        this.execLogRepo = execLogRepo;
        this.metrics = metrics;
    }

    @Override
    public boolean supports(DiagAction act) {
        return act == DiagAction.EXECUTION_END;
    }

    @Override
    @Transactional
    public void handle(String topic, DiagMessage<?> envelope) {
        if (!(envelope.payload() instanceof JsonNode payload)) {
            log.warn("execution_end payload 非 JsonNode topic={} payloadType={}",
                    topic, envelope.payload() == null ? "null" : envelope.payload().getClass().getName());
            return;
        }

        String taskId = extractText(payload, "taskId");
        Integer executionSeq = extractInt(payload, "executionSeq");

        if (taskId == null || executionSeq == null) {
            log.warn("execution_end 缺少必填字段 topic={} taskId={} seq={}",
                    topic, taskId, executionSeq);
            return;
        }

        String vin = envelope.vin();
        String endMsgId = envelope.msgId();
        Integer overallStatus = extractInt(payload, "overallStatus");
        Integer executionDuration = extractInt(payload, "executionDuration");
        String resultPayloadJson = buildResultPayloadJson(payload);

        // UPDATE WHERE end_reported_at IS NULL (幂等: 已有 end 的不覆盖)
        int affected = execLogRepo.updateEnd(
                endMsgId, overallStatus, resultPayloadJson,
                executionDuration, taskId, vin, executionSeq);

        if (affected > 0) {
            metrics.recordEnd(taskId, overallStatus != null ? overallStatus : 0);
            log.info("execution_end 已处理 taskId={} vin={} seq={} overallStatus={} duration={}ms",
                    taskId, vin, executionSeq, overallStatus, executionDuration);
        } else {
            // begin 未到达(网络乱序)或已回填(车端重传)
            log.warn("execution_end 未匹配到 open begin taskId={} vin={} seq={} endMsgId={}",
                    taskId, vin, executionSeq, endMsgId);
        }
    }

    /**
     * 构建结果 JSON:提取 payload 中的结果相关字段。
     */
    private String buildResultPayloadJson(JsonNode payload) {
        JsonNode results = payload.get("results");
        if (results != null && !results.isNull()) {
            return results.toString();
        }
        // 降级:提取 status + errorCode + msg
        StringBuilder sb = new StringBuilder("{");
        Integer status = extractInt(payload, "overallStatus");
        if (status != null) {
            sb.append("\"overallStatus\":").append(status);
        }
        String errorCode = extractText(payload, "errorCode");
        if (errorCode != null) {
            if (sb.length() > 1) sb.append(',');
            sb.append("\"errorCode\":\"").append(escapeJson(errorCode)).append('"');
        }
        String msg = extractText(payload, "msg");
        if (msg != null) {
            if (sb.length() > 1) sb.append(',');
            sb.append("\"msg\":\"").append(escapeJson(msg)).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String extractText(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child != null && !child.isNull() ? child.asText() : null;
    }

    private static Integer extractInt(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child != null && !child.isNull() ? child.asInt() : null;
    }
}

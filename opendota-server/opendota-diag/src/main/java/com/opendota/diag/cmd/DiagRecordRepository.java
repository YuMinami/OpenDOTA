package com.opendota.diag.cmd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * {@code diag_record} 访问层。
 *
 * <p>Step 2.3 使用策略:
 * <ul>
 *   <li>下发前先写一条 pending 记录,作为响应关联锚点</li>
 *   <li>{@code single_resp} 到达后按 {@code msgId=cmdId} 完成 upsert 更新</li>
 * </ul>
 */
@Repository
public class DiagRecordRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DiagRecordRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insertPendingSingleCmd(PendingSingleCmd pending) {
        jdbc.update("""
                INSERT INTO diag_record (
                    msg_id, tenant_id, vin, ecu_name, act, req_raw_hex, status,
                    operator_id, operator_role, ticket_id, trace_id
                ) VALUES (?, ?, ?, ?, ?, ?, -1, ?, ?, ?, ?)
                """,
                pending.msgId(),
                pending.tenantId(),
                pending.vin(),
                pending.ecuName(),
                pending.act(),
                pending.reqRawHex(),
                pending.operatorId(),
                pending.operatorRole(),
                pending.ticketId(),
                pending.traceId());
    }

    public Optional<DiagRecordContext> findByMsgId(String msgId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, msg_id, tenant_id, vin, ecu_name, req_raw_hex,
                           operator_id, operator_role, ticket_id, trace_id
                    FROM diag_record
                    WHERE msg_id = ?
                    """, CONTEXT_MAPPER, msgId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public long upsertSingleResponse(CompletedSingleCmd completed) {
        String translatedJson = toJson(completed.translated());
        Long id = jdbc.queryForObject("""
                INSERT INTO diag_record (
                    msg_id, tenant_id, vin, ecu_name, act, req_raw_hex,
                    res_raw_hex, translated, status, error_code,
                    operator_id, operator_role, ticket_id, trace_id, responded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (msg_id) DO UPDATE SET
                    vin = EXCLUDED.vin,
                    ecu_name = COALESCE(diag_record.ecu_name, EXCLUDED.ecu_name),
                    req_raw_hex = COALESCE(diag_record.req_raw_hex, EXCLUDED.req_raw_hex),
                    res_raw_hex = EXCLUDED.res_raw_hex,
                    translated = EXCLUDED.translated,
                    status = EXCLUDED.status,
                    error_code = EXCLUDED.error_code,
                    operator_id = COALESCE(diag_record.operator_id, EXCLUDED.operator_id),
                    operator_role = COALESCE(diag_record.operator_role, EXCLUDED.operator_role),
                    ticket_id = COALESCE(diag_record.ticket_id, EXCLUDED.ticket_id),
                    trace_id = COALESCE(diag_record.trace_id, EXCLUDED.trace_id),
                    responded_at = EXCLUDED.responded_at
                RETURNING id
                """,
                Long.class,
                completed.msgId(),
                completed.tenantId(),
                completed.vin(),
                completed.ecuName(),
                completed.act(),
                completed.reqRawHex(),
                completed.resRawHex(),
                translatedJson,
                completed.status(),
                completed.errorCode(),
                completed.operatorId(),
                completed.operatorRole(),
                completed.ticketId(),
                completed.traceId(),
                Timestamp.from(completed.respondedAt()));
        if (id == null) {
            throw new IllegalStateException("diag_record upsert 未返回主键: msgId=" + completed.msgId());
        }
        return id;
    }

    public void deleteByMsgId(String msgId) {
        jdbc.update("DELETE FROM diag_record WHERE msg_id = ?", msgId);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("diag_record.translated JSON 序列化失败", e);
        }
    }

    private static final RowMapper<DiagRecordContext> CONTEXT_MAPPER = (rs, rowNum) -> new DiagRecordContext(
            rs.getLong("id"),
            rs.getString("msg_id"),
            rs.getString("tenant_id"),
            rs.getString("vin"),
            rs.getString("ecu_name"),
            rs.getString("req_raw_hex"),
            rs.getString("operator_id"),
            rs.getString("operator_role"),
            rs.getString("ticket_id"),
            rs.getString("trace_id"));

    public record PendingSingleCmd(
            String msgId,
            String tenantId,
            String vin,
            String ecuName,
            String act,
            String reqRawHex,
            String operatorId,
            String operatorRole,
            String ticketId,
            String traceId) {
    }

    /**
     * 批量命令 pending 记录(Step 3.1)。
     */
    public void insertPendingBatchCmd(PendingBatchCmd pending) {
        String stepsJson = toJson(pending.steps());
        jdbc.update("""
                INSERT INTO diag_record (
                    msg_id, tenant_id, vin, ecu_name, act, req_raw_hex, status,
                    operator_id, operator_role, ticket_id, trace_id
                ) VALUES (?, ?, ?, ?, ?, ?, -1, ?, ?, ?, ?)
                """,
                pending.msgId(),
                pending.tenantId(),
                pending.vin(),
                pending.ecuName(),
                pending.act(),
                stepsJson,
                pending.operatorId(),
                pending.operatorRole(),
                pending.ticketId(),
                pending.traceId());
    }

    /**
     * 批量响应 upsert(Step 3.1)。
     *
     * <p>按 msgId 关联 pending diag_record,更新响应数据和翻译结果。
     */
    public long upsertBatchResponse(CompletedBatchCmd completed) {
        String resultsJson = toJson(completed.results());
        String translatedJson = toJson(completed.translated());
        Long id = jdbc.queryForObject("""
                INSERT INTO diag_record (
                    msg_id, tenant_id, vin, ecu_name, act, req_raw_hex,
                    res_raw_hex, translated, status, error_code,
                    operator_id, operator_role, ticket_id, trace_id, responded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (msg_id) DO UPDATE SET
                    vin = EXCLUDED.vin,
                    ecu_name = COALESCE(diag_record.ecu_name, EXCLUDED.ecu_name),
                    res_raw_hex = EXCLUDED.res_raw_hex,
                    translated = EXCLUDED.translated,
                    status = EXCLUDED.status,
                    error_code = EXCLUDED.error_code,
                    responded_at = EXCLUDED.responded_at
                RETURNING id
                """,
                Long.class,
                completed.msgId(),
                completed.tenantId(),
                completed.vin(),
                completed.ecuName(),
                completed.act(),
                completed.reqRawHex(),
                resultsJson,
                translatedJson,
                completed.overallStatus(),
                completed.errorCode(),
                completed.operatorId(),
                completed.operatorRole(),
                completed.ticketId(),
                completed.traceId(),
                Timestamp.from(completed.respondedAt()));
        if (id == null) {
            throw new IllegalStateException("diag_record batch upsert 未返回主键: msgId=" + completed.msgId());
        }
        return id;
    }

    public record PendingBatchCmd(
            String msgId,
            String tenantId,
            String vin,
            String ecuName,
            String act,
            Map<String, Object> steps,
            String operatorId,
            String operatorRole,
            String ticketId,
            String traceId) {
    }

    public record CompletedBatchCmd(
            String msgId,
            String tenantId,
            String vin,
            String ecuName,
            String act,
            String reqRawHex,
            Map<String, Object> results,
            Integer overallStatus,
            String errorCode,
            String operatorId,
            String operatorRole,
            String ticketId,
            String traceId,
            Instant respondedAt,
            Map<String, Object> translated) {
    }

    public record CompletedSingleCmd(
            String msgId,
            String tenantId,
            String vin,
            String ecuName,
            String act,
            String reqRawHex,
            String resRawHex,
            Integer status,
            String errorCode,
            String operatorId,
            String operatorRole,
            String ticketId,
            String traceId,
            Instant respondedAt,
            Map<String, Object> translated) {
    }

    public record DiagRecordContext(
            Long id,
            String msgId,
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

package com.opendota.diag.cmd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

/**
 * {@code sse_event} 事件流水访问层。
 */
@Repository
public class SseEventRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SseEventRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long insertDiagResult(String tenantId, String vin, long sourceId, Map<String, Object> payloadSummary) {
        String summaryJson = toJson(payloadSummary);
        Long id = jdbc.queryForObject("""
                INSERT INTO sse_event (
                    tenant_id, vin, event_type, source_type, source_id, payload_summary
                ) VALUES (?, ?, 'diag-result', 'diag_record', ?, CAST(? AS jsonb))
                RETURNING id
                """,
                Long.class,
                tenantId,
                vin,
                sourceId,
                summaryJson);
        if (id == null) {
            throw new IllegalStateException("sse_event insert 未返回主键: vin=" + vin + ", sourceId=" + sourceId);
        }
        return id;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("sse_event.payload_summary JSON 序列化失败", e);
        }
    }
}

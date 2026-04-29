package com.opendota.diag.cmd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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

    public List<StoredSseEvent> findReplayEvents(String vin, long lastEventId, int limit) {
        return jdbc.query("""
                SELECT id, tenant_id, vin, event_type, source_type, source_id, payload_summary, created_at
                FROM sse_event
                WHERE vin = ?
                  AND id > ?
                ORDER BY id ASC
                LIMIT ?
                """,
                EVENT_ROW_MAPPER,
                vin,
                lastEventId,
                limit);
    }

    public List<StoredSseEvent> findAfterId(long lastScannedId, int limit) {
        return jdbc.query("""
                SELECT id, tenant_id, vin, event_type, source_type, source_id, payload_summary, created_at
                FROM sse_event
                WHERE id > ?
                ORDER BY id ASC
                LIMIT ?
                """,
                EVENT_ROW_MAPPER,
                lastScannedId,
                limit);
    }

    public long findMaxId() {
        Long maxId = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM sse_event", Long.class);
        return maxId == null ? 0L : maxId;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("sse_event.payload_summary JSON 序列化失败", e);
        }
    }

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RowMapper<StoredSseEvent> EVENT_ROW_MAPPER = (rs, rowNum) -> new StoredSseEvent(
            rs.getLong("id"),
            rs.getString("tenant_id"),
            rs.getString("vin"),
            rs.getString("event_type"),
            rs.getString("source_type"),
            rs.getLong("source_id"),
            fromJson(rs.getString("payload_summary")),
            toInstant(rs.getTimestamp("created_at")));

    private Map<String, Object> fromJson(String payload) {
        try {
            return objectMapper.readValue(payload, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("sse_event.payload_summary JSON 反序列化失败", e);
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record StoredSseEvent(
            long id,
            String tenantId,
            String vin,
            String eventType,
            String sourceType,
            Long sourceId,
            Map<String, Object> payloadSummary,
            Instant createdAt) {
    }
}

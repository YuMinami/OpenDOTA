package com.opendota.task.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * Outbox 事件自定义仓库,使用 FOR UPDATE SKIP LOCKED 实现多节点无冲突消费。
 *
 * <p>架构 §3.3.1.1 要求:
 * <ul>
 *   <li>多节点部署时 SKIP LOCKED 保证每行只被一个节点抓取</li>
 *   <li>只拉取 status='new' 或 (status='failed' 且 next_retry_at 已到) 的记录</li>
 *   <li>单次拉取上限 500 条,防止长事务</li>
 * </ul>
 */
@Repository
public class OutboxEventCustomRepository {

    private static final String SELECT_PENDING_SQL = """
            SELECT id, tenant_id, aggregate_type, aggregate_id, event_type,
                   payload::TEXT, kafka_topic, kafka_key, attempts
            FROM outbox_event
            WHERE status IN ('new', 'failed')
              AND (next_retry_at IS NULL OR next_retry_at <= NOW())
            ORDER BY id
            LIMIT 500
            FOR UPDATE SKIP LOCKED
            """;

    private final JdbcTemplate jdbc;

    public OutboxEventCustomRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 拉取一批待投递事件(带行锁,多节点安全)。
     */
    public List<OutboxRow> fetchPending() {
        return jdbc.query(SELECT_PENDING_SQL, this::mapRow);
    }

    private OutboxRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxRow(
                rs.getLong("id"),
                rs.getString("tenant_id"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getString("kafka_topic"),
                rs.getString("kafka_key"),
                rs.getInt("attempts")
        );
    }
}

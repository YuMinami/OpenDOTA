package com.opendota.task.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.task.outbox.DispatchCommand;
import com.opendota.task.outbox.KafkaTopicProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * pending_online 任务回放服务。
 *
 * <p>提取自 {@link OnlineEventConsumer} 和 {@link ReconcileOnlineStatusJob} 的共享逻辑,
 * 查询指定 VIN 的 pending_online 分发记录,转换为 {@link DispatchCommand} 发布到
 * {@code task-dispatch-queue} topic,由分发调度器统一处理限流 + jitter。
 *
 * <p><strong>Phase 4 DoD #3 — offline_task_push_latency</strong>:
 * 每条回放成功的 dispatch_record 都按 {@code now() - pending_online_at} 打一次延迟样本,
 * 用于 P95 SLO 监控。打点后清空 {@code pending_online_at} 避免二次回放重复采样。
 */
@Service
public class PendingTaskReplayService {

    private static final Logger log = LoggerFactory.getLogger(PendingTaskReplayService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTopicProperties topicProps;
    private final LifecycleMetrics metrics;

    public PendingTaskReplayService(JdbcTemplate jdbcTemplate,
                                    ObjectMapper objectMapper,
                                    KafkaTemplate<String, String> kafkaTemplate,
                                    KafkaTopicProperties topicProps,
                                    LifecycleMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.topicProps = topicProps;
        this.metrics = metrics;
    }

    /**
     * 回放指定 VIN 的所有 pending_online 分发记录(架构 §4.5)。
     *
     * @param vin 上线车辆的 VIN
     * @return 回放的任务数量
     */
    public int replayPendingTasks(String vin) {
        List<PendingReplayRow> rows = jdbcTemplate.query(
                """
                SELECT d.id AS dispatch_id, d.task_id, d.tenant_id, d.ecu_scope,
                       d.pending_online_at,
                       t.priority, t.schedule_type, t.payload_type, t.diag_payload, t.payload_hash
                FROM task_dispatch_record d
                JOIN task_definition t ON d.task_id = t.task_id
                WHERE d.vin = ? AND d.dispatch_status = 'pending_online'
                AND (t.valid_until IS NULL OR t.valid_until > NOW())
                AND t.status = 'active'
                """,
                (rs, rowNum) -> {
                    DispatchCommand cmd = new DispatchCommand(
                            rs.getString("task_id"),
                            rs.getString("tenant_id"),
                            vin,
                            rs.getString("ecu_scope"),
                            rs.getInt("priority"),
                            rs.getString("schedule_type"),
                            rs.getString("payload_type"),
                            rs.getString("diag_payload"),
                            rs.getString("payload_hash")
                    );
                    Timestamp pendingAt = rs.getTimestamp("pending_online_at");
                    long dispatchId = rs.getLong("dispatch_id");
                    return new PendingReplayRow(dispatchId, cmd, pendingAt);
                },
                vin
        );

        if (rows.isEmpty()) {
            log.debug("无 pending_online 任务 vin={}", vin);
            return 0;
        }

        log.info("回放 {} 条 pending_online 任务 vin={}", rows.size(), vin);

        long nowMillis = System.currentTimeMillis();
        int replayed = 0;
        for (PendingReplayRow row : rows) {
            try {
                String payload = objectMapper.writeValueAsString(row.cmd());
                kafkaTemplate.send(topicProps.getDispatchQueue(), row.cmd().taskId(), payload);
                metrics.recordReplayTriggered();

                // Phase 4 DoD #3: 打点 offline_task_push_latency 并清空基准时间戳
                if (row.pendingAt() != null) {
                    long latencyMs = nowMillis - row.pendingAt().getTime();
                    metrics.recordOfflineTaskPushLatency(latencyMs);
                }
                jdbcTemplate.update(
                        "UPDATE task_dispatch_record SET pending_online_at = NULL WHERE id = ?",
                        row.dispatchId());

                replayed++;
                log.debug("回放分发指令已发布 taskId={} vin={} latencyMs={}",
                        row.cmd().taskId(), vin,
                        row.pendingAt() == null ? -1 : (nowMillis - row.pendingAt().getTime()));
            } catch (JsonProcessingException e) {
                log.error("序列化 DispatchCommand 失败 taskId={} vin={}: {}",
                        row.cmd().taskId(), vin, e.getMessage());
            }
        }
        return replayed;
    }

    /** 行级 DTO,内部使用。 */
    private record PendingReplayRow(long dispatchId, DispatchCommand cmd, Timestamp pendingAt) {}
}

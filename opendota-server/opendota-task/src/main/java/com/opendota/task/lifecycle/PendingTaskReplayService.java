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

import java.util.List;

/**
 * pending_online 任务回放服务。
 *
 * <p>提取自 {@link OnlineEventConsumer} 和 {@link ReconcileOnlineStatusJob} 的共享逻辑,
 * 查询指定 VIN 的 pending_online 分发记录,转换为 {@link DispatchCommand} 发布到
 * {@code task-dispatch-queue} topic,由分发调度器统一处理限流 + jitter。
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
        List<DispatchCommand> commands = jdbcTemplate.query(
                """
                SELECT d.task_id, d.tenant_id, d.ecu_scope, t.priority, t.schedule_type, t.payload_type, t.diag_payload, t.payload_hash
                FROM task_dispatch_record d
                JOIN task_definition t ON d.task_id = t.task_id
                WHERE d.vin = ? AND d.dispatch_status = 'pending_online'
                AND (t.valid_until IS NULL OR t.valid_until > NOW())
                AND t.status = 'active'
                """,
                (rs, rowNum) -> new DispatchCommand(
                        rs.getString("task_id"),
                        rs.getString("tenant_id"),
                        vin,
                        rs.getString("ecu_scope"),
                        rs.getInt("priority"),
                        rs.getString("schedule_type"),
                        rs.getString("payload_type"),
                        rs.getString("diag_payload"),
                        rs.getString("payload_hash")
                ),
                vin
        );

        if (commands.isEmpty()) {
            log.debug("无 pending_online 任务 vin={}", vin);
            return 0;
        }

        log.info("回放 {} 条 pending_online 任务 vin={}", commands.size(), vin);

        int replayed = 0;
        for (DispatchCommand cmd : commands) {
            try {
                String payload = objectMapper.writeValueAsString(cmd);
                kafkaTemplate.send(topicProps.getDispatchQueue(), cmd.taskId(), payload);
                metrics.recordReplayTriggered();
                replayed++;
                log.debug("回放分发指令已发布 taskId={} vin={}", cmd.taskId(), vin);
            } catch (JsonProcessingException e) {
                log.error("序列化 DispatchCommand 失败 taskId={} vin={}: {}", cmd.taskId(), vin, e.getMessage());
            }
        }
        return replayed;
    }
}

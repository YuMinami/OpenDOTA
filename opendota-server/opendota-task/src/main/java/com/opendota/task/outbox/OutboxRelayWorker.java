package com.opendota.task.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 中继 Worker(架构 §3.3.1.1)。
 *
 * <p>核心职责:
 * <ol>
 *   <li>每 200ms 轮询 outbox_event 表中 status='new' 或 (status='failed' 且 next_retry_at 已到) 的记录</li>
 *   <li>使用 FOR UPDATE SKIP LOCKED 保证多节点部署不冲突</li>
 *   <li>逐条投递到 Kafka(同步等 ack),成功后标 sent,失败后标 failed + 指数退避</li>
 *   <li>Kafka Producer 启用幂等(enable.idempotence=true),防止网络抖动导致重复</li>
 * </ol>
 *
 * <p>指数退避策略: {@code next_retry_at = NOW() + min(attempts, 8) * 30s}
 * <p>最大退避间隔 ~4 分钟,防止积压事件垄断扫描窗口。
 */
@Component
public class OutboxRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayWorker.class);

    private static final int MAX_BACKOFF_EXPONENT = 8;
    private static final int BACKOFF_BASE_SECONDS = 30;
    private static final long KAFKA_SEND_TIMEOUT_SECONDS = 5;

    private final OutboxEventCustomRepository customRepo;
    private final OutboxEventRepository outboxRepo;
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxMetrics metrics;

    public OutboxRelayWorker(OutboxEventCustomRepository customRepo,
                             OutboxEventRepository outboxRepo,
                             JdbcTemplate jdbc,
                             KafkaTemplate<String, String> kafka,
                             OutboxMetrics metrics) {
        this.customRepo = customRepo;
        this.outboxRepo = outboxRepo;
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.metrics = metrics;
    }

    /**
     * 扫描并投递待处理 outbox 事件。
     *
     * <p>fixedDelay=200ms,可按负载调至 100ms。
     * <p>每批最多 500 条,单条失败不影响整批。
     */
    @Scheduled(fixedDelayString = "${opendota.outbox.relay.fixed-delay:200}")
    @Transactional
    public void drain() {
        Timer.Sample sample = metrics.startDrainTimer();
        try {
            List<OutboxRow> rows = customRepo.fetchPending();
            if (rows.isEmpty()) {
                return;
            }

            log.debug("Outbox relay 拉取 {} 条待投递事件", rows.size());

            int sentCount = 0;
            for (OutboxRow row : rows) {
                try {
                    publishToKafka(row);
                    outboxRepo.markSent(row.id(), LocalDateTime.now());
                    sentCount++;
                } catch (Exception e) {
                    handleFailure(row, e);
                }
            }

            if (sentCount > 0) {
                metrics.recordSent(sentCount);
                log.debug("Outbox relay 本批成功投递 {} 条", sentCount);
            }
        } finally {
            metrics.stopDrainTimer(sample);
        }
    }

    /**
     * 构建 Kafka ProducerRecord 并同步发送。
     *
     * <p>Headers 遵循架构 §6.3.3:
     * <ul>
     *   <li>tenant-id (必填)</li>
     *   <li>event-type (必填)</li>
     *   <li>event-id (必填,用于去重)</li>
     *   <li>schema-version = "1" (必填)</li>
     * </ul>
     */
    private void publishToKafka(OutboxRow row) throws Exception {
        String key = row.kafkaKey() != null ? row.kafkaKey() : row.aggregateId();

        ProducerRecord<String, String> record = new ProducerRecord<>(
                row.kafkaTopic(), key, row.payload());

        // 架构 §6.3.3 固定 headers
        record.headers()
                .add(new RecordHeader("tenant-id", row.tenantId().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader("event-type", row.eventType().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader("event-id", Long.toString(row.id()).getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader("schema-version", "1".getBytes(StandardCharsets.UTF_8)));

        // 同步等 Kafka ack,超时 5 秒
        kafka.send(record).get(KAFKA_SEND_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 投递失败处理:标记 failed + 指数退避。
     *
     * <p>退避公式: {@code next_retry_at = NOW() + min(attempts, 8) * 30s}
     */
    private void handleFailure(OutboxRow row, Exception e) {
        int newAttempts = row.attempts() + 1;
        int exponent = Math.min(newAttempts, MAX_BACKOFF_EXPONENT);
        LocalDateTime nextRetryAt = LocalDateTime.now().plusSeconds((long) exponent * BACKOFF_BASE_SECONDS);
        String lastError = truncate(e.getMessage(), 512);

        outboxRepo.markFailed(row.id(), nextRetryAt, lastError);
        metrics.recordFailed();

        log.warn("Outbox relay 投递失败: id={}, topic={}, attempts={}, nextRetryAt={}, error={}",
                row.id(), row.kafkaTopic(), newAttempts, nextRetryAt, lastError);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}

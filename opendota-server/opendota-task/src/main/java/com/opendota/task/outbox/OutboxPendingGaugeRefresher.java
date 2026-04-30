package com.opendota.task.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期刷新 {@code outbox_pending_total} gauge(Phase 4 DoD #4)。
 *
 * <p>对 outbox_event 表按 status 维度做轻量 COUNT,把结果灌入 {@link OutboxMetrics}。
 * Prometheus 每 10 秒抓一次 /actuator/prometheus,Grafana 据此画"队列深度"曲线。
 *
 * <p>设计取舍:
 * <ul>
 *   <li>不用 Postgres FDW / pg_exporter — 应用侧已经有 JdbcTemplate,加一个 SQL 而已</li>
 *   <li>{@code @Scheduled(fixedDelay=15000)} 与 Prometheus 抓取频率(10s)对齐,避免 gauge 抖动</li>
 *   <li>失败容忍:任何 SQL 异常吞掉,只打 WARN 日志,不抛出影响其他定时任务</li>
 * </ul>
 */
@Component
public class OutboxPendingGaugeRefresher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPendingGaugeRefresher.class);

    private final JdbcTemplate jdbcTemplate;
    private final OutboxMetrics metrics;

    public OutboxPendingGaugeRefresher(JdbcTemplate jdbcTemplate, OutboxMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelay = 15_000L, initialDelay = 5_000L)
    public void refresh() {
        try {
            Long pendingNew = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE status = 'new'",
                    Long.class);
            Long pendingFailed = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE status = 'failed'",
                    Long.class);
            Long sentRecent = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE status = 'sent' "
                            + "AND sent_at IS NOT NULL AND sent_at > NOW() - INTERVAL '5 minutes'",
                    Long.class);
            metrics.updatePendingDepth(
                    pendingNew == null ? 0L : pendingNew,
                    pendingFailed == null ? 0L : pendingFailed,
                    sentRecent == null ? 0L : sentRecent);
            if (log.isDebugEnabled()) {
                log.debug("outbox_pending_total 刷新: new={} failed={} sent_recent={}",
                        pendingNew, pendingFailed, sentRecent);
            }
        } catch (DataAccessException e) {
            log.warn("刷新 outbox_pending_total gauge 失败,保留上次值: {}", e.getMessage());
        }
    }
}

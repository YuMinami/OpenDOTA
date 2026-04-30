package com.opendota.task.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Outbox 归档作业(架构 §3.3.1.1 指标 4)。
 *
 * <p>每天凌晨 3 点执行一次,将 status='sent' 且 sent_at 超过 7 天的记录标记为 'archived'。
 * <p>防止 outbox_event 表无限膨胀。生产环境可配合分区表或定期 VACUUM。
 */
@Component
public class OutboxArchiveJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxArchiveJob.class);

    private static final int RETENTION_DAYS = 7;

    private final OutboxEventRepository outboxRepo;

    public OutboxArchiveJob(OutboxEventRepository outboxRepo) {
        this.outboxRepo = outboxRepo;
    }

    /**
     * 每天凌晨 3 点归档已发送超过 7 天的 outbox 事件。
     */
    @Scheduled(cron = "${opendota.outbox.archive.cron:0 0 3 * * ?}")
    @Transactional
    public void archive() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        int archived = outboxRepo.archiveOlderThan(cutoff);
        if (archived > 0) {
            log.info("Outbox 归档完成: 归档 {} 条(sent_at < {})", archived, cutoff);
        }
    }
}

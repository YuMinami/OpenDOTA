package com.opendota.task.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 事件 JPA 仓库。
 *
 * <p>pending 查询使用原生 SQL + FOR UPDATE SKIP LOCKED,
 * 由 {@link OutboxEventCustomRepository} 实现。
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Modifying
    @Query("UPDATE OutboxEvent o SET o.status = 'sent', o.sentAt = :sentAt WHERE o.id = :id")
    void markSent(@Param("id") Long id, @Param("sentAt") LocalDateTime sentAt);

    @Modifying
    @Query("UPDATE OutboxEvent o SET o.status = 'failed', o.attempts = o.attempts + 1, " +
           "o.nextRetryAt = :nextRetryAt, o.lastError = :lastError WHERE o.id = :id")
    void markFailed(@Param("id") Long id, @Param("nextRetryAt") LocalDateTime nextRetryAt,
                    @Param("lastError") String lastError);

    @Modifying
    @Query("UPDATE OutboxEvent o SET o.status = 'archived' WHERE o.status = 'sent' AND o.sentAt < :cutoff")
    int archiveOlderThan(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT COUNT(o) FROM OutboxEvent o WHERE o.status = :status")
    long countByStatus(@Param("status") String status);
}

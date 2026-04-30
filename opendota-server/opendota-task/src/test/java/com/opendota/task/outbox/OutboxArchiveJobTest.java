package com.opendota.task.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OutboxArchiveJob 单元测试。
 */
class OutboxArchiveJobTest {

    private OutboxEventRepository outboxRepo;
    private OutboxArchiveJob archiveJob;

    @BeforeEach
    void setUp() {
        outboxRepo = mock(OutboxEventRepository.class);
        archiveJob = new OutboxArchiveJob(outboxRepo);
    }

    @Test
    void archive_noOldEvents_noAction() {
        when(outboxRepo.archiveOlderThan(any(LocalDateTime.class))).thenReturn(0);

        archiveJob.archive();

        verify(outboxRepo).archiveOlderThan(any(LocalDateTime.class));
    }

    @Test
    void archive_oldEvents_archived() {
        when(outboxRepo.archiveOlderThan(any(LocalDateTime.class))).thenReturn(42);

        archiveJob.archive();

        verify(outboxRepo).archiveOlderThan(any(LocalDateTime.class));
    }
}

package com.opendota.task.execution;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ExecutionReconcileJob 单元测试。
 */
class ExecutionReconcileJobTest {

    private TaskExecutionLogRepository execLogRepo;
    private JdbcTemplate jdbc;
    private MeterRegistry meterRegistry;
    private ExecutionReconcileJob job;

    @BeforeEach
    void setUp() {
        execLogRepo = mock(TaskExecutionLogRepository.class);
        jdbc = mock(JdbcTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        job = new ExecutionReconcileJob(execLogRepo, jdbc, meterRegistry);
    }

    @Test
    void reconcile_noTimedOutRecords_noop() {
        when(execLogRepo.findTimedOutExecutions()).thenReturn(List.of());

        job.reconcile();

        verify(execLogRepo).findTimedOutExecutions();
        verifyNoMoreInteractions(execLogRepo);
        assertEquals(0.0, meterRegistry.find("dota_execution_timeout_total").counter().count());
    }

    @Test
    void reconcile_timedOutRecord_marksAsFailed() {
        TaskExecutionLogRepository.ExecutionBeginRecord record =
                new TaskExecutionLogRepository.ExecutionBeginRecord(
                        "task-cron-001", "LSVWA234567890123", 7,
                        LocalDateTime.now().minusMinutes(30), "default-tenant");

        when(execLogRepo.findTimedOutExecutions()).thenReturn(List.of(record));
        when(execLogRepo.markTimedOutAsFailed("task-cron-001", "LSVWA234567890123", 7, "default-tenant"))
                .thenReturn(1);

        job.reconcile();

        verify(execLogRepo).markTimedOutAsFailed("task-cron-001", "LSVWA234567890123", 7, "default-tenant");
        assertEquals(1.0, meterRegistry.find("dota_execution_timeout_total").counter().count());
    }

    @Test
    void reconcile_multipleTimedOut_allMarked() {
        List<TaskExecutionLogRepository.ExecutionBeginRecord> records = List.of(
                new TaskExecutionLogRepository.ExecutionBeginRecord(
                        "task-cron-001", "LSVWA234567890123", 7,
                        LocalDateTime.now().minusMinutes(30), "tenant-a"),
                new TaskExecutionLogRepository.ExecutionBeginRecord(
                        "task-cron-001", "LSVWB234567890456", 3,
                        LocalDateTime.now().minusMinutes(20), "tenant-a"),
                new TaskExecutionLogRepository.ExecutionBeginRecord(
                        "task-cron-002", "LSVWA234567890123", 12,
                        LocalDateTime.now().minusHours(1), "tenant-b")
        );

        when(execLogRepo.findTimedOutExecutions()).thenReturn(records);
        when(execLogRepo.markTimedOutAsFailed(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(1);

        job.reconcile();

        verify(execLogRepo).markTimedOutAsFailed("task-cron-001", "LSVWA234567890123", 7, "tenant-a");
        verify(execLogRepo).markTimedOutAsFailed("task-cron-001", "LSVWB234567890456", 3, "tenant-a");
        verify(execLogRepo).markTimedOutAsFailed("task-cron-002", "LSVWA234567890123", 12, "tenant-b");
        assertEquals(3.0, meterRegistry.find("dota_execution_timeout_total").counter().count());
    }

    @Test
    void reconcile_markFails_continuesProcessing() {
        List<TaskExecutionLogRepository.ExecutionBeginRecord> records = List.of(
                new TaskExecutionLogRepository.ExecutionBeginRecord(
                        "task-cron-001", "LSVWA234567890123", 7,
                        LocalDateTime.now().minusMinutes(30), "tenant-a"),
                new TaskExecutionLogRepository.ExecutionBeginRecord(
                        "task-cron-001", "LSVWB234567890456", 3,
                        LocalDateTime.now().minusMinutes(20), "tenant-a")
        );

        when(execLogRepo.findTimedOutExecutions()).thenReturn(records);
        // 第一条抛异常,第二条成功
        when(execLogRepo.markTimedOutAsFailed(eq("task-cron-001"), eq("LSVWA234567890123"), eq(7), anyString()))
                .thenThrow(new RuntimeException("DB error"));
        when(execLogRepo.markTimedOutAsFailed(eq("task-cron-001"), eq("LSVWB234567890456"), eq(3), anyString()))
                .thenReturn(1);

        job.reconcile();

        // 第二条仍然被处理
        assertEquals(1.0, meterRegistry.find("dota_execution_timeout_total").counter().count());
    }

    @Test
    void reconcile_alreadyEnded_noop() {
        TaskExecutionLogRepository.ExecutionBeginRecord record =
                new TaskExecutionLogRepository.ExecutionBeginRecord(
                        "task-cron-001", "LSVWA234567890123", 7,
                        LocalDateTime.now().minusMinutes(30), "default-tenant");

        when(execLogRepo.findTimedOutExecutions()).thenReturn(List.of(record));
        // end_reported_at IS NULL 不再满足 → affected=0
        when(execLogRepo.markTimedOutAsFailed("task-cron-001", "LSVWA234567890123", 7, "default-tenant"))
                .thenReturn(0);

        job.reconcile();

        // 不计数(已被 end 回填)
        assertEquals(0.0, meterRegistry.find("dota_execution_timeout_total").counter().count());
    }
}

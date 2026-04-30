package com.opendota.task.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TaskExecutionLogRepository 单元测试。
 */
class TaskExecutionLogRepositoryTest {

    private JdbcTemplate jdbc;
    private TaskExecutionLogRepository repo;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        repo = new TaskExecutionLogRepository(jdbc);
    }

    @Test
    void insertBegin_newRecord_returnsOne() {
        when(jdbc.update(contains("INSERT INTO task_execution_log"),
                eq("tenant-a"), eq("task-001"), eq("LSVWA234567890123"),
                eq(7), eq(1713300300000L), eq("msg-begin-001")))
                .thenReturn(1);

        int affected = repo.insertBegin("tenant-a", "task-001", "LSVWA234567890123",
                7, 1713300300000L, "msg-begin-001");

        assertEquals(1, affected);
    }

    @Test
    void insertBegin_duplicate_returnsZero() {
        when(jdbc.update(contains("INSERT INTO task_execution_log"),
                anyString(), anyString(), anyString(), anyInt(), anyLong(), anyString()))
                .thenReturn(0);

        int affected = repo.insertBegin("tenant-a", "task-001", "LSVWA234567890123",
                7, 1713300300000L, "msg-begin-001");

        assertEquals(0, affected);
    }

    @Test
    void updateEnd_matched_returnsOne() {
        when(jdbc.update(contains("UPDATE task_execution_log"),
                eq("msg-end-001"), eq(0), anyString(), eq(5200),
                eq("task-001"), eq("LSVWA234567890123"), eq(7)))
                .thenReturn(1);

        int affected = repo.updateEnd("msg-end-001", 0, "{}", 5200,
                "task-001", "LSVWA234567890123", 7);

        assertEquals(1, affected);
    }

    @Test
    void updateEnd_noMatch_returnsZero() {
        when(jdbc.update(contains("UPDATE task_execution_log"),
                anyString(), anyInt(), anyString(), any(),
                anyString(), anyString(), anyInt()))
                .thenReturn(0);

        int affected = repo.updateEnd("msg-end-001", 0, "{}", 5200,
                "task-001", "LSVWA234567890123", 99);

        assertEquals(0, affected);
    }

    @Test
    void markTimedOutAsFailed_setsStatusAndError() {
        when(jdbc.update(contains("UPDATE task_execution_log"),
                eq("task-001"), eq("LSVWA234567890123"), eq(7)))
                .thenReturn(1);

        int affected = repo.markTimedOutAsFailed("task-001", "LSVWA234567890123", 7, "tenant-a");

        assertEquals(1, affected);
    }

    @Test
    void findTimedOutExecutions_delegatesToJdbc() {
        when(jdbc.query(contains("task_execution_log"), any(RowMapper.class)))
                .thenReturn(java.util.List.of());

        var result = repo.findTimedOutExecutions();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}

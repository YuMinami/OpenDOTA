package com.opendota.task.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OutboxPendingGaugeRefresher 单元测试 — Phase 4 DoD #4。
 */
class OutboxPendingGaugeRefresherTest {

    private JdbcTemplate jdbc;
    private OutboxMetrics metrics;
    private OutboxPendingGaugeRefresher refresher;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        MeterRegistry registry = new SimpleMeterRegistry();
        metrics = new OutboxMetrics(registry);
        refresher = new OutboxPendingGaugeRefresher(jdbc, metrics);
    }

    @Test
    void refresh_normalCase_updatesAllThreeGauges() {
        when(jdbc.queryForObject(contains("status = 'new'"), eq(Long.class))).thenReturn(120L);
        when(jdbc.queryForObject(contains("status = 'failed'"), eq(Long.class))).thenReturn(7L);
        when(jdbc.queryForObject(contains("status = 'sent'"), eq(Long.class))).thenReturn(45L);

        refresher.refresh();

        assertEquals(120L, metrics.pendingNew());
        assertEquals(7L, metrics.pendingFailed());
        assertEquals(45L, metrics.sentRecent());
    }

    @Test
    void refresh_nullResult_treatedAsZero() {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);

        refresher.refresh();

        assertEquals(0L, metrics.pendingNew());
        assertEquals(0L, metrics.pendingFailed());
        assertEquals(0L, metrics.sentRecent());
    }

    @Test
    void refresh_dataAccessException_swallowedNotPropagated() {
        when(jdbc.queryForObject(anyString(), eq(Long.class)))
                .thenThrow(new DataAccessResourceFailureException("PG 不可达"));

        // 不应抛出
        assertDoesNotThrow(refresher::refresh);
        // 上次值保留(初始 0)
        assertEquals(0L, metrics.pendingNew());
    }

    @Test
    void refresh_largeDepth_doesNotOverflow() {
        when(jdbc.queryForObject(contains("status = 'new'"), eq(Long.class))).thenReturn(1_000_000L);
        when(jdbc.queryForObject(contains("status = 'failed'"), eq(Long.class))).thenReturn(50_000L);
        when(jdbc.queryForObject(contains("status = 'sent'"), eq(Long.class))).thenReturn(0L);

        refresher.refresh();

        assertEquals(1_000_000L, metrics.pendingNew());
        assertEquals(50_000L, metrics.pendingFailed());
    }

    @Test
    void outboxMetrics_pendingTotalGauge_registeredWithStatusTag() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new OutboxMetrics(registry); // 触发注册

        var newGauge = registry.find("outbox_pending_total").tag("status", "new").gauge();
        var failedGauge = registry.find("outbox_pending_total").tag("status", "failed").gauge();
        var sentRecentGauge = registry.find("outbox_pending_total").tag("status", "sent_recent").gauge();

        assertNotNull(newGauge, "outbox_pending_total{status=new} 必须注册");
        assertNotNull(failedGauge, "outbox_pending_total{status=failed} 必须注册");
        assertNotNull(sentRecentGauge, "outbox_pending_total{status=sent_recent} 必须注册");
    }
}

package com.opendota.security.tenant;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantRlsAspectTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final TenantRlsAspect aspect = new TenantRlsAspect(jdbcTemplate);
    private final ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void injectTenantId_withTenantId_setsConfig() throws Throwable {
        TenantContext.set("tenant-a");
        when(pjp.proceed()).thenReturn("result");

        Object result = aspect.injectTenantId(pjp);

        assertEquals("result", result);
        verify(jdbcTemplate).execute(eq("SELECT set_config('app.tenant_id', ?, true)"), any(PreparedStatementCallback.class));
    }

    @Test
    void injectTenantId_noTenantId_skipsConfig() throws Throwable {
        TenantContext.clear();
        when(pjp.proceed()).thenReturn("result");

        Object result = aspect.injectTenantId(pjp);

        assertEquals("result", result);
        verify(jdbcTemplate, never()).execute(anyString(), any(PreparedStatementCallback.class));
    }

    @Test
    void injectTenantId_blankTenantId_skipsConfig() throws Throwable {
        TenantContext.set("   ");
        when(pjp.proceed()).thenReturn("result");

        Object result = aspect.injectTenantId(pjp);

        assertEquals("result", result);
        verify(jdbcTemplate, never()).execute(anyString(), any(PreparedStatementCallback.class));
    }

    @Test
    void injectTenantId_jdbcException_continuesExecution() throws Throwable {
        TenantContext.set("tenant-a");
        when(jdbcTemplate.execute(anyString(), any(PreparedStatementCallback.class)))
                .thenThrow(new RuntimeException("DB error"));
        when(pjp.proceed()).thenReturn("result");

        Object result = aspect.injectTenantId(pjp);
        assertEquals("result", result);
    }
}

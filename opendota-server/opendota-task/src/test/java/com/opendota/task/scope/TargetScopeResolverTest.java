package com.opendota.task.scope;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TargetScopeResolver 单元测试。
 */
class TargetScopeResolverTest {

    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private TargetScopeResolver resolver;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        objectMapper = new ObjectMapper();
        resolver = new TargetScopeResolver(jdbcTemplate, objectMapper);
    }

    @Test
    void resolve_vinList_returnsDirectVins() {
        String targetValue = "{\"type\":\"vin_list\",\"mode\":\"snapshot\",\"value\":[\"LSVWA234567890123\",\"LSVWB234567890456\"]}";

        List<String> result = resolver.resolve("vin_list", targetValue, "default");

        assertEquals(2, result.size());
        assertEquals("LSVWA234567890123", result.get(0));
        assertEquals("LSVWB234567890456", result.get(1));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void resolve_vinList_emptyArray_returnsEmpty() {
        String targetValue = "{\"type\":\"vin_list\",\"mode\":\"snapshot\",\"value\":[]}";

        List<String> result = resolver.resolve("vin_list", targetValue, "default");

        assertTrue(result.isEmpty());
    }

    @Test
    void resolve_vinList_invalidJson_returnsEmpty() {
        List<String> result = resolver.resolve("vin_list", "not-json", "default");

        assertTrue(result.isEmpty());
    }

    @Test
    void resolve_all_queriesByTenant() {
        when(jdbcTemplate.queryForList(
                eq("SELECT vin FROM vehicle_online_status WHERE tenant_id = ?"),
                eq(String.class), eq("tenant-1")))
                .thenReturn(List.of("LSVWA234567890123", "LSVWB234567890456"));

        List<String> result = resolver.resolve("all", "{}", "tenant-1");

        assertEquals(2, result.size());
        verify(jdbcTemplate).queryForList(anyString(), eq(String.class), eq("tenant-1"));
    }

    @Test
    void resolve_model_queriesByTenant() {
        when(jdbcTemplate.queryForList(
                eq("SELECT vin FROM vehicle_online_status WHERE tenant_id = ?"),
                eq(String.class), eq("default")))
                .thenReturn(List.of("LSVWA234567890123"));

        List<String> result = resolver.resolve("model", "{\"modelCode\":\"CHERY_EXEED\"}", "default");

        assertEquals(1, result.size());
    }

    @Test
    void resolve_tag_returnsEmpty() {
        List<String> result = resolver.resolve("tag", "{}", "default");

        assertTrue(result.isEmpty());
    }

    @Test
    void resolve_unknownType_returnsEmpty() {
        List<String> result = resolver.resolve("unknown", "{}", "default");

        assertTrue(result.isEmpty());
    }

    @Test
    void resolve_nullType_returnsEmpty() {
        List<String> result = resolver.resolve(null, "{}", "default");

        assertTrue(result.isEmpty());
    }
}

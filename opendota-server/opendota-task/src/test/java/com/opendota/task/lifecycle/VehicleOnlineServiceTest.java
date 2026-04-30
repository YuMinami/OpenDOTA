package com.opendota.task.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * VehicleOnlineService 单元测试。
 *
 * <p>markOnline/markOffline 通过 OnlineEventConsumerTest 间接覆盖(行为测试)。
 * 此处仅测试无 varargs 匹配问题的查询方法。
 */
class VehicleOnlineServiceTest {

    private JdbcTemplate jdbcTemplate;
    private VehicleOnlineService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new VehicleOnlineService(jdbcTemplate);
    }

    @Test
    void findAllOnlineVins_returnsList() {
        doReturn(List.of("LSVWA234567890123", "LSVWB987654321098"))
                .when(jdbcTemplate).queryForList(anyString(), eq(String.class));

        List<String> vins = service.findAllOnlineVins();

        assertEquals(2, vins.size());
        assertTrue(vins.contains("LSVWA234567890123"));
        assertTrue(vins.contains("LSVWB987654321098"));
    }

    @Test
    void findAllOnlineVins_emptyList() {
        doReturn(List.of())
                .when(jdbcTemplate).queryForList(anyString(), eq(String.class));

        List<String> vins = service.findAllOnlineVins();

        assertTrue(vins.isEmpty());
    }

    @Test
    void isOnline_onlineVehicle_returnsTrue() {
        doReturn(1).when(jdbcTemplate).queryForObject(anyString(), eq(Integer.class), anyString());

        assertTrue(service.isOnline("LSVWA234567890123"));
    }

    @Test
    void isOnline_offlineVehicle_returnsFalse() {
        doReturn(0).when(jdbcTemplate).queryForObject(anyString(), eq(Integer.class), anyString());

        assertFalse(service.isOnline("LSVWA234567890123"));
    }

    @Test
    void isOnline_nullCount_returnsFalse() {
        doReturn(null).when(jdbcTemplate).queryForObject(anyString(), eq(Integer.class), anyString());

        assertFalse(service.isOnline("LSVWA234567890123"));
    }
}

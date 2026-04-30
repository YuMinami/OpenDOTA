package com.opendota.task.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 车辆在线状态管理服务(架构 §4.6.1)。
 *
 * <p>直接操作 {@code vehicle_online_status} 表,不走 JPA(免 RLS 上下文依赖)。
 * <p>所有写操作都是幂等的:重复的 connected/disconnected 事件不会产生副作用。
 */
@Service
public class VehicleOnlineService {

    private static final Logger log = LoggerFactory.getLogger(VehicleOnlineService.class);

    private final JdbcTemplate jdbcTemplate;

    public VehicleOnlineService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 标记车辆上线(幂等)。
     *
     * <p>如果 {@code vehicle_online_status} 中不存在该 VIN,会自动插入一行。
     *
     * @param vin         车辆 VIN
     * @param clientId    MQTT Client ID(可为 null)
     * @param onlineAt    上线时间
     * @return 是否实际更新了状态(0 = 已经在线,1 = 新上线)
     */
    public int markOnline(String vin, String clientId, Instant onlineAt) {
        LocalDateTime ts = LocalDateTime.ofInstant(onlineAt, ZoneId.systemDefault());
        // UPSERT: 存在则更新,不存在则插入
        int updated = jdbcTemplate.update("""
                INSERT INTO vehicle_online_status (vin, tenant_id, is_online, last_online_at, mqtt_client_id, updated_at)
                VALUES (?, 'default', TRUE, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (vin) DO UPDATE SET
                    is_online = TRUE,
                    last_online_at = GREATEST(vehicle_online_status.last_online_at, EXCLUDED.last_online_at),
                    mqtt_client_id = EXCLUDED.mqtt_client_id,
                    updated_at = CURRENT_TIMESTAMP
                WHERE vehicle_online_status.is_online = FALSE
                """, vin, ts, clientId);
        if (updated > 0) {
            log.info("车辆上线 vin={} clientId={} at={}", vin, clientId, onlineAt);
        }
        return updated;
    }

    /**
     * 标记车辆离线(幂等)。
     *
     * @param vin         车辆 VIN
     * @param offlineAt   离线时间
     * @return 是否实际更新了状态(0 = 已经离线,1 = 新离线)
     */
    public int markOffline(String vin, Instant offlineAt) {
        LocalDateTime ts = LocalDateTime.ofInstant(offlineAt, ZoneId.systemDefault());
        int updated = jdbcTemplate.update("""
                UPDATE vehicle_online_status
                SET is_online = FALSE,
                    last_offline_at = GREATEST(last_offline_at, ?),
                    updated_at = CURRENT_TIMESTAMP
                WHERE vin = ? AND is_online = TRUE
                """, ts, vin);
        if (updated > 0) {
            log.info("车辆离线 vin={} at={}", vin, offlineAt);
        }
        return updated;
    }

    /**
     * 查询所有在线车辆的 VIN 列表。
     */
    public List<String> findAllOnlineVins() {
        return jdbcTemplate.queryForList(
                "SELECT vin FROM vehicle_online_status WHERE is_online = TRUE",
                String.class);
    }

    /**
     * 检查指定 VIN 是否在线。
     */
    public boolean isOnline(String vin) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vehicle_online_status WHERE vin = ? AND is_online = TRUE",
                Integer.class, vin);
        return count != null && count > 0;
    }
}

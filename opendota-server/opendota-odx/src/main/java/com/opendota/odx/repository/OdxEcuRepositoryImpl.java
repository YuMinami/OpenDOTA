package com.opendota.odx.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.odx.entity.OdxDiagService;
import com.opendota.odx.entity.OdxEcu;
import com.opendota.odx.entity.OdxParamCodec;
import com.opendota.odx.entity.OdxVehicleModel;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * {@link OdxEcuRepository} 的 JdbcTemplate 实现(Phase 2 Step 2.2)。
 *
 * <p>设计取舍:
 * <ul>
 *   <li>JdbcTemplate 直读,不上 JPA(架构 §1.2、计划 Step 2.2 实现要点)。</li>
 *   <li>{@code gateway_chain} 是 JSONB 列(V3 迁移),用 Jackson 解出 {@code List<String>}。</li>
 * </ul>
 */
@Repository
public class OdxEcuRepositoryImpl implements OdxEcuRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OdxEcuRepositoryImpl(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<OdxVehicleModel> findVehicleModelById(long modelId) {
        return queryOptional(
                "SELECT id, tenant_id, model_code, model_name, odx_version "
                        + "FROM odx_vehicle_model WHERE id = ?",
                VEHICLE_MODEL_MAPPER, modelId);
    }

    @Override
    public Optional<OdxVehicleModel> findVehicleModelByVin(String vin) {
        return queryOptional(
                "SELECT m.id, m.tenant_id, m.model_code, m.model_name, m.odx_version "
                        + "FROM odx_vehicle_model m "
                        + "JOIN vehicle_online_status v ON v.tenant_id = m.tenant_id "
                        + "WHERE v.vin = ? "
                        + "ORDER BY m.id ASC LIMIT 1",
                VEHICLE_MODEL_MAPPER, vin);
    }

    @Override
    public Optional<OdxEcu> findEcuById(long ecuId) {
        return queryOptional(
                "SELECT id, model_id, ecu_code, ecu_name, tx_id, rx_id, protocol, "
                        + "       sec_algo_ref, gateway_chain "
                        + "FROM odx_ecu WHERE id = ?",
                ecuMapper(), ecuId);
    }

    @Override
    public List<OdxEcu> findEcusByModelId(long modelId) {
        return jdbc.query(
                "SELECT id, model_id, ecu_code, ecu_name, tx_id, rx_id, protocol, "
                        + "       sec_algo_ref, gateway_chain "
                        + "FROM odx_ecu WHERE model_id = ? ORDER BY id ASC",
                ecuMapper(), modelId);
    }

    @Override
    public Optional<OdxEcu> findEcuByName(long modelId, String ecuName) {
        return queryOptional(
                "SELECT id, model_id, ecu_code, ecu_name, tx_id, rx_id, protocol, "
                        + "       sec_algo_ref, gateway_chain "
                        + "FROM odx_ecu "
                        + "WHERE model_id = ? AND (ecu_name = ? OR ecu_name LIKE ? || ' %') "
                        + "ORDER BY id ASC LIMIT 1",
                ecuMapper(), modelId, ecuName, ecuName);
    }

    @Override
    public List<OdxDiagService> findServicesByEcuId(long ecuId) {
        return jdbc.query(
                "SELECT id, ecu_id, service_code, sub_function, service_name, display_name, "
                        + "       description, category, request_raw_hex, response_id_hex, "
                        + "       requires_security, required_sec_level, required_session, "
                        + "       macro_type, is_enabled, safety_critical "
                        + "FROM odx_diag_service "
                        + "WHERE ecu_id = ? "
                        + "ORDER BY category ASC, id ASC",
                DIAG_SERVICE_MAPPER, ecuId);
    }

    @Override
    public List<OdxParamCodec> findParamCodecsByServiceId(long serviceId) {
        return jdbc.query(
                "SELECT id, service_id, param_name, display_name, byte_offset, bit_offset, "
                        + "       bit_length, data_type, formula, unit, enum_mapping, "
                        + "       min_value, max_value "
                        + "FROM odx_param_codec "
                        + "WHERE service_id = ? "
                        + "ORDER BY byte_offset ASC, bit_offset ASC, id ASC",
                PARAM_CODEC_MAPPER, serviceId);
    }

    // ============================================================
    // RowMappers
    // ============================================================

    private static final RowMapper<OdxVehicleModel> VEHICLE_MODEL_MAPPER = (rs, n) -> new OdxVehicleModel(
            rs.getLong("id"),
            rs.getString("tenant_id"),
            rs.getString("model_code"),
            rs.getString("model_name"),
            rs.getString("odx_version"));

    /** ECU mapper 依赖 ObjectMapper 解析 JSONB 列,用闭包绑定实例,避免静态 holder。 */
    private RowMapper<OdxEcu> ecuMapper() {
        return (rs, n) -> new OdxEcu(
                rs.getLong("id"),
                rs.getLong("model_id"),
                rs.getString("ecu_code"),
                rs.getString("ecu_name"),
                rs.getString("tx_id"),
                rs.getString("rx_id"),
                rs.getString("protocol"),
                rs.getString("sec_algo_ref"),
                parseStringArray(rs, "gateway_chain"));
    }

    private static final RowMapper<OdxDiagService> DIAG_SERVICE_MAPPER = (rs, n) -> new OdxDiagService(
            rs.getLong("id"),
            rs.getLong("ecu_id"),
            rs.getString("service_code"),
            rs.getString("sub_function"),
            rs.getString("service_name"),
            rs.getString("display_name"),
            rs.getString("description"),
            rs.getString("category"),
            rs.getString("request_raw_hex"),
            rs.getString("response_id_hex"),
            getNullableBoolean(rs, "requires_security"),
            rs.getString("required_sec_level"),
            rs.getString("required_session"),
            rs.getString("macro_type"),
            getNullableBoolean(rs, "is_enabled"),
            getNullableBoolean(rs, "safety_critical"));

    private static final RowMapper<OdxParamCodec> PARAM_CODEC_MAPPER = (rs, n) -> new OdxParamCodec(
            rs.getLong("id"),
            rs.getLong("service_id"),
            rs.getString("param_name"),
            rs.getString("display_name"),
            rs.getInt("byte_offset"),
            rs.getInt("bit_offset"),
            rs.getInt("bit_length"),
            rs.getString("data_type"),
            rs.getString("formula"),
            rs.getString("unit"),
            rs.getString("enum_mapping"),
            rs.getBigDecimal("min_value"),
            rs.getBigDecimal("max_value"));

    private List<String> parseStringArray(ResultSet rs, String column) throws SQLException {
        String raw = rs.getString(column);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(raw, STRING_LIST);
            return parsed == null ? List.of() : parsed;
        } catch (JsonProcessingException e) {
            throw new SQLException("解析 JSONB 列 " + column + " 失败: " + raw, e);
        }
    }

    private static Boolean getNullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private <T> Optional<T> queryOptional(String sql, RowMapper<T> mapper, Object... args) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, mapper, args));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }
}

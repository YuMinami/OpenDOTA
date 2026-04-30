package com.opendota.task.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 目标范围解析器。
 *
 * <p>将 task_target 的 targetType + targetValue 解析为具体 VIN 列表。
 * <p>使用 JdbcTemplate 直接查询(绕过 JPA/RLS),与 {@code VehicleOnlineService} 同模式。
 *
 * <p>支持的 targetType:
 * <ul>
 *   <li>{@code vin_list} — 直接从 targetValue JSON 数组取 VIN</li>
 *   <li>{@code all} — 该租户下所有车辆</li>
 *   <li>{@code model} — 按车型过滤(当前降级为租户全部车辆)</li>
 *   <li>{@code tag} — 按标签过滤(当前未实现,返回空)</li>
 * </ul>
 */
@Service
public class TargetScopeResolver {

    private static final Logger log = LoggerFactory.getLogger(TargetScopeResolver.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TargetScopeResolver(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 解析目标范围为 VIN 列表。
     *
     * @param targetType target_target.target_type (vin_list / model / tag / all)
     * @param targetValue target_target.target_value (JSONB 字符串)
     * @param tenantId    租户 ID
     * @return 匹配的 VIN 列表(不可变)
     */
    public List<String> resolve(String targetType, String targetValue, String tenantId) {
        if (targetType == null) {
            return Collections.emptyList();
        }
        return switch (targetType) {
            case "vin_list" -> resolveVinList(targetValue);
            case "all" -> resolveAll(tenantId);
            case "model" -> resolveModel(targetValue, tenantId);
            case "tag" -> {
                log.warn("tag 类型 targetScope 暂未实现");
                yield Collections.emptyList();
            }
            default -> {
                log.warn("未知的 targetType: {}", targetType);
                yield Collections.emptyList();
            }
        };
    }

    private List<String> resolveVinList(String targetValue) {
        try {
            JsonNode node = objectMapper.readTree(targetValue);
            JsonNode valueNode = node.get("value");
            if (valueNode == null || !valueNode.isArray()) {
                // 兼容 targetValue 本身就是数组的情况
                if (node.isArray()) {
                    return parseJsonArray(node);
                }
                return Collections.emptyList();
            }
            return parseJsonArray(valueNode);
        } catch (Exception e) {
            log.warn("解析 vin_list targetValue 失败: {}", targetValue, e);
            return Collections.emptyList();
        }
    }

    private List<String> resolveAll(String tenantId) {
        return jdbcTemplate.queryForList(
                "SELECT vin FROM vehicle_online_status WHERE tenant_id = ?",
                String.class, tenantId);
    }

    private List<String> resolveModel(String targetValue, String tenantId) {
        // TODO: 有 vehicle-model 映射表后按车型过滤,当前降级为租户全部车辆
        return jdbcTemplate.queryForList(
                "SELECT vin FROM vehicle_online_status WHERE tenant_id = ?",
                String.class, tenantId);
    }

    private List<String> parseJsonArray(JsonNode arrayNode) {
        List<String> result = new ArrayList<>(arrayNode.size());
        arrayNode.forEach(n -> result.add(n.asText()));
        return Collections.unmodifiableList(result);
    }
}

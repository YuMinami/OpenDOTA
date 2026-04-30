package com.opendota.task.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 车辆在线状态对账作业(架构 §4.6.4)。
 *
 * <p>每 5 分钟扫描 EMQX REST API 获取当前在线客户端列表,
 * 与 {@code vehicle_online_status} 表比对,修正因 Kafka 事件丢失导致的不一致。
 *
 * <p>修正逻辑:
 * <ul>
 *   <li>EMQX 在线但 PG 离线 → 标记上线 + 触发 pending_online 回放</li>
 *   <li>EMQX 离线但 PG 在线 → 标记离线</li>
 * </ul>
 *
 * <p>通过 {@code opendota.lifecycle.reconcile.enabled} 控制开关,默认启用。
 * 本地开发环境可关闭以避免 EMQX 不可用时的错误日志。
 */
@Component
@ConditionalOnProperty(name = "opendota.lifecycle.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class ReconcileOnlineStatusJob {

    private static final Logger log = LoggerFactory.getLogger(ReconcileOnlineStatusJob.class);

    private final VehicleOnlineService vehicleOnlineService;
    private final PendingTaskReplayService replayService;
    private final LifecycleMetrics metrics;
    private final ObjectMapper objectMapper;

    @Value("${opendota.lifecycle.reconcile.emqx-api-url:http://localhost:18083/api/v5}")
    private String emqxApiUrl;

    @Value("${opendota.lifecycle.reconcile.emqx-api-key:}")
    private String emqxApiKey;

    @Value("${opendota.lifecycle.reconcile.emqx-api-secret:}")
    private String emqxApiSecret;

    public ReconcileOnlineStatusJob(VehicleOnlineService vehicleOnlineService,
                                    PendingTaskReplayService replayService,
                                    LifecycleMetrics metrics,
                                    ObjectMapper objectMapper) {
        this.vehicleOnlineService = vehicleOnlineService;
        this.replayService = replayService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    /**
     * 每 5 分钟执行一次对账。
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void reconcileOnlineStatus() {
        Timer.Sample sample = metrics.startReconcile();
        try {
            doReconcile();
        } catch (Exception e) {
            log.error("车辆在线状态对账失败: {}", e.getMessage(), e);
        } finally {
            metrics.recordReconcileTime(sample);
        }
    }

    private void doReconcile() {
        // 1. 从 EMQX 获取在线 VIN 集合
        Set<String> emqxOnlineVins = fetchEmqxClients();
        if (emqxOnlineVins == null) {
            log.warn("无法获取 EMQX 在线客户端列表,跳过本次对账");
            return;
        }

        // 2. 从 PG 获取在线 VIN 列表
        List<String> pgOnlineVins = vehicleOnlineService.findAllOnlineVins();
        Set<String> pgOnlineSet = new HashSet<>(pgOnlineVins);

        // 3. 修正:EMQX 在线但 PG 离线 → 标记上线 + 触发回放
        int onlineFixed = 0;
        for (String vin : emqxOnlineVins) {
            if (!pgOnlineSet.contains(vin)) {
                int updated = vehicleOnlineService.markOnline(vin, null, Instant.now());
                if (updated > 0) {
                    onlineFixed++;
                    log.info("对账修正:标记上线 vin={}", vin);
                    replayService.replayPendingTasks(vin);
                }
            }
        }

        // 4. 修正:EMQX 离线但 PG 在线 → 标记离线
        int offlineFixed = 0;
        for (String vin : pgOnlineVins) {
            if (!emqxOnlineVins.contains(vin)) {
                int updated = vehicleOnlineService.markOffline(vin, Instant.now());
                if (updated > 0) {
                    offlineFixed++;
                    log.info("对账修正:标记离线 vin={}", vin);
                }
            }
        }

        if (onlineFixed > 0 || offlineFixed > 0) {
            log.warn("对账完成:emqxOnline={} pgOnline={} 修正上线={} 修正离线={}",
                    emqxOnlineVins.size(), pgOnlineVins.size(), onlineFixed, offlineFixed);
        } else {
            log.debug("对账完成:状态一致 emqxOnline={} pgOnline={}", emqxOnlineVins.size(), pgOnlineVins.size());
        }
    }

    /**
     * 调用 EMQX REST API 获取当前在线客户端 VIN 列表。
     *
     * <p>EMQX 5.x API: {@code GET /api/v5/clients?limit=10000}
     * 返回 JSON: {@code {"data": [{"username":"VIN1"}, {"username":"VIN2"}, ...]}}
     *
     * @return 在线 VIN 集合,调用失败返回 null
     */
    private Set<String> fetchEmqxClients() {
        try {
            RestTemplate restTemplate = new RestTemplate();
            String url = emqxApiUrl + "/clients?limit=10000";

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            if (emqxApiKey != null && !emqxApiKey.isEmpty()) {
                headers.setBasicAuth(emqxApiKey, emqxApiSecret);
            }
            org.springframework.http.HttpEntity<Void> entity = new org.springframework.http.HttpEntity<>(headers);

            var response = restTemplate.exchange(
                    url,
                    org.springframework.http.HttpMethod.GET,
                    entity,
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("EMQX API 响应异常: status={}", response.getStatusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode dataArray = root.path("data");
            if (!dataArray.isArray()) {
                log.warn("EMQX API 响应格式异常:data 不是数组");
                return null;
            }

            Set<String> vins = new HashSet<>();
            for (JsonNode client : dataArray) {
                // username 字段存储的是 VIN(EMQX 认证配置: username = VIN)
                String username = client.path("username").asText(null);
                if (username != null && !username.isEmpty()) {
                    vins.add(username);
                }
            }
            return vins;
        } catch (Exception e) {
            log.error("调用 EMQX API 失败: {}", e.getMessage());
            return null;
        }
    }
}

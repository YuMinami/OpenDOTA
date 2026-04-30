package com.opendota.task.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.task.entity.TaskDefinition;
import com.opendota.task.entity.TaskDispatchRecord;
import com.opendota.task.entity.TaskTarget;
import com.opendota.task.outbox.DispatchCommand;
import com.opendota.task.outbox.OutboxService;
import com.opendota.task.repository.TaskDefinitionRepository;
import com.opendota.task.repository.TaskDispatchRecordRepository;
import com.opendota.task.repository.TaskTargetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 动态目标范围定时扫描器(架构 §4.4.4.1)。
 *
 * <p>每小时扫描一次 {@code mode='dynamic'} 的 task_target,
 * 对新匹配但无 dispatch_record 的 VIN 自动补发分发记录。
 *
 * <p>适用场景:新下线车辆自动纳入活跃的周期/条件任务。
 *
 * <p>并发安全:
 * <ul>
 *   <li>使用 {@code FOR UPDATE SKIP LOCKED} 防止多节点同时处理同一目标</li>
 *   <li>{@code task_dispatch_record(task_id, vin)} UNIQUE 约束兜底防重</li>
 * </ul>
 *
 * <p>首次扫描时 dynamic_last_resolved_at 为空,视为全量补发;后续增量。
 * 任务终态(completed/expired/canceled)后自动跳过(WHERE status='active')。
 */
@Component
public class DynamicTargetScopeWorker {

    private static final Logger log = LoggerFactory.getLogger(DynamicTargetScopeWorker.class);

    private final JdbcTemplate jdbcTemplate;
    private final TaskTargetRepository taskTargetRepo;
    private final TaskDefinitionRepository taskDefRepo;
    private final TaskDispatchRecordRepository dispatchRecordRepo;
    private final TargetScopeResolver scopeResolver;
    private final OutboxService outboxService;
    private final ScopeMetrics metrics;
    private final ObjectMapper objectMapper;

    public DynamicTargetScopeWorker(JdbcTemplate jdbcTemplate,
                                    TaskTargetRepository taskTargetRepo,
                                    TaskDefinitionRepository taskDefRepo,
                                    TaskDispatchRecordRepository dispatchRecordRepo,
                                    TargetScopeResolver scopeResolver,
                                    OutboxService outboxService,
                                    ScopeMetrics metrics,
                                    ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.taskTargetRepo = taskTargetRepo;
        this.taskDefRepo = taskDefRepo;
        this.dispatchRecordRepo = dispatchRecordRepo;
        this.scopeResolver = scopeResolver;
        this.outboxService = outboxService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    /**
     * 每小时第 5 分钟执行一次扫描(架构 §4.4.4.1)。
     */
    @Scheduled(cron = "0 5 * * * ?")
    public void scan() {
        var sample = metrics.startScan();
        try {
            doScan();
            metrics.recordScan();
        } catch (Exception e) {
            log.error("DynamicTargetScopeWorker 扫描异常", e);
            metrics.recordError();
        } finally {
            metrics.recordScanDuration(sample);
        }
    }

    /**
     * 扫描逻辑。
     * <p>使用 JDBC FOR UPDATE SKIP LOCKED 获取动态目标,逐个处理。
     * 每个目标独立事务,单个失败不影响其余。
     */
    private void doScan() {
        // 1. 使用 FOR UPDATE SKIP LOCKED 获取待扫描的 dynamic 目标 ID
        List<Long> targetIds = jdbcTemplate.queryForList("""
                SELECT tt.id FROM task_target tt
                JOIN task_definition td ON td.task_id = tt.task_id
                WHERE tt.mode = 'dynamic'
                  AND td.status = 'active'
                  AND (td.valid_until IS NULL OR td.valid_until > NOW())
                ORDER BY tt.dynamic_last_resolved_at NULLS FIRST
                FOR UPDATE OF tt SKIP LOCKED
                """, Long.class);

        if (targetIds.isEmpty()) {
            log.debug("无 dynamic 目标需要扫描");
            return;
        }

        log.info("DynamicTargetScopeWorker 开始扫描: {} 个 dynamic 目标", targetIds.size());

        // 2. 逐个处理(独立事务)
        for (Long targetId : targetIds) {
            try {
                processTarget(targetId);
            } catch (Exception e) {
                log.error("处理 dynamic 目标失败: targetId={}", targetId, e);
                metrics.recordError();
            }
        }
    }

    /**
     * 处理单个 dynamic 目标(独立事务)。
     */
    @Transactional
    public void processTarget(Long targetId) {
        TaskTarget target = taskTargetRepo.findById(targetId).orElse(null);
        if (target == null || target.getTaskDefinition() == null) {
            log.warn("dynamic 目标不存在或未关联任务: targetId={}", targetId);
            return;
        }

        TaskDefinition def = target.getTaskDefinition();
        // 二次校验:任务仍为 active 且未过期
        if (!"active".equals(def.getStatus())) {
            log.debug("任务已非 active,跳过: taskId={}, status={}", def.getTaskId(), def.getStatus());
            return;
        }
        if (def.getValidUntil() != null && def.getValidUntil().isBefore(LocalDateTime.now())) {
            log.debug("任务已过期,跳过: taskId={}", def.getTaskId());
            return;
        }

        String taskId = def.getTaskId();
        String tenantId = def.getTenantId();

        // 1. 解析当前匹配的 VIN 集合
        List<String> currentVins = scopeResolver.resolve(
                target.getTargetType(), target.getTargetValue(), tenantId);
        if (currentVins.isEmpty()) {
            log.debug("目标范围解析为空: taskId={}, targetType={}", taskId, target.getTargetType());
            updateScanMetadata(target);
            return;
        }

        // 2. 查询已存在的 VIN 集合
        Set<String> existingVins = dispatchRecordRepo.findExistingVinsByTaskId(taskId);

        // 3. 差集:新匹配但无 dispatch_record 的 VIN
        List<String> newcomers = currentVins.stream()
                .filter(vin -> !existingVins.contains(vin))
                .toList();

        if (newcomers.isEmpty()) {
            log.debug("无新 VIN: taskId={}, currentSize={}", taskId, currentVins.size());
            updateScanMetadata(target);
            return;
        }

        // 4. 提取 ecuScope 用于 dispatch_record
        String ecuScope = extractEcuScope(def.getDiagPayload());

        // 5. 批量创建 dispatch_record + outbox 事件
        List<TaskDispatchRecord> newRecords = newcomers.stream()
                .map(vin -> new TaskDispatchRecord(taskId, tenantId, vin, ecuScope))
                .toList();
        dispatchRecordRepo.saveAll(newRecords);

        String diagPayloadJson = def.getDiagPayload();
        for (TaskDispatchRecord record : newRecords) {
            DispatchCommand cmd = new DispatchCommand(
                    taskId, tenantId, record.getVin(),
                    ecuScope, def.getPriority(),
                    def.getScheduleType(), def.getPayloadType(),
                    diagPayloadJson, def.getPayloadHash());
            outboxService.enqueueDispatchCommand(tenantId, taskId, cmd);
        }

        // 6. 更新扫描元数据
        updateScanMetadata(target);

        metrics.recordNewcomer(newcomers.size());
        log.info("DynamicTargetScopeWorker 新增 VIN: taskId={}, newcomers={}, totalCurrent={}",
                taskId, newcomers.size(), currentVins.size());
    }

    /**
     * 更新 dynamic 目标的扫描元数据。
     */
    private void updateScanMetadata(TaskTarget target) {
        LocalDateTime now = LocalDateTime.now();
        target.setDynamicLastResolvedAt(now);
        target.setDynamicResolutionCount(
                (target.getDynamicResolutionCount() == null ? 0 : target.getDynamicResolutionCount()) + 1);
        taskTargetRepo.save(target);
    }

    /**
     * 从 diagPayload JSON 中提取 ecuScope。
     */
    private String extractEcuScope(String diagPayloadJson) {
        try {
            JsonNode node = objectMapper.readTree(diagPayloadJson);
            JsonNode ecuScopeNode = node.get("ecuScope");
            if (ecuScopeNode != null && !ecuScopeNode.isNull()) {
                return ecuScopeNode.toString();
            }
        } catch (Exception e) {
            log.warn("提取 ecuScope 失败", e);
        }
        return "[]";
    }
}

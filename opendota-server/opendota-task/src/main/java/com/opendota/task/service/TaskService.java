package com.opendota.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.web.ApiError;
import com.opendota.common.web.BusinessException;
import com.opendota.task.controller.TaskController;
import com.opendota.task.entity.TaskDefinition;
import com.opendota.task.entity.TaskDispatchRecord;
import com.opendota.task.entity.TaskTarget;
import com.opendota.task.outbox.DispatchCommand;
import com.opendota.task.outbox.OutboxService;
import com.opendota.task.repository.TaskDefinitionRepository;
import com.opendota.task.repository.TaskDispatchRecordRepository;
import com.opendota.task.repository.TaskTargetRepository;
import com.opendota.task.scope.TargetScopeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 任务定义核心服务(REST §6、协议 §8.2)。
 *
 * <p>职责:
 * <ol>
 *   <li>创建任务时执行全部验证规则(R1-R3 + E41001-E41012)</li>
 *   <li>计算/校验 payloadHash</li>
 *   <li>自动推导 missPolicy</li>
 *   <li>解析 targetScope 为 VIN 列表并生成 dispatch_record</li>
 *   <li>任务 CRUD (查询/暂停/恢复/取消/版本链)</li>
 * </ol>
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskDefinitionRepository taskDefRepo;
    private final TaskTargetRepository taskTargetRepo;
    private final TaskDispatchRecordRepository dispatchRecordRepo;
    private final ObjectMapper objectMapper;
    private final OutboxService outboxService;
    private final TargetScopeResolver scopeResolver;

    public TaskService(TaskDefinitionRepository taskDefRepo,
                       TaskTargetRepository taskTargetRepo,
                       TaskDispatchRecordRepository dispatchRecordRepo,
                       ObjectMapper objectMapper,
                       OutboxService outboxService,
                       TargetScopeResolver scopeResolver) {
        this.taskDefRepo = taskDefRepo;
        this.taskTargetRepo = taskTargetRepo;
        this.dispatchRecordRepo = dispatchRecordRepo;
        this.objectMapper = objectMapper;
        this.outboxService = outboxService;
        this.scopeResolver = scopeResolver;
    }

    /**
     * 创建任务(POST /task)。
     * 在同一事务内: 校验 → 计算 hash → 解析目标 → 持久化 definition + target + dispatch_record
     */
    @Transactional
    public TaskController.CreateTaskResponse createTask(TaskController.CreateTaskRequest req, String operatorId, String tenantId) {
        // 1. 基础校验
        validateCreateRequest(req);

        // 2. 校验 R1: maxExecutions=-1 时 executeValidUntil 必填
        validateAntiPerpetual(req);

        // 3. 校验时间窗口
        validateTimeWindows(req);

        // 4. 校验 scheduleType 与 scheduleConfig 一致性 (R3/E41004)
        validateScheduleConsistency(req);

        // 5. 校验 conditional 任务的 signalCatalogVersion (E41006)
        if ("conditional".equals(req.scheduleType())) {
            validateConditionalTask(req);
        }

        // 6. 校验 dynamic targetScope 仅允许 periodic/conditional (E41012)
        if (req.targetScope() != null && "dynamic".equals(req.targetScope().mode())) {
            if (!"periodic".equals(req.scheduleType()) && !"conditional".equals(req.scheduleType())) {
                throw new BusinessException(ApiError.E41012,
                        "dynamic targetScope 仅允许 periodic/conditional scheduleType");
            }
        }

        // 7. 计算 payloadHash
        String computedHash = computePayloadHash(req.diagPayload());
        if (req.payloadHash() != null && !req.payloadHash().isBlank()) {
            if (!req.payloadHash().equalsIgnoreCase(computedHash)) {
                throw new BusinessException(ApiError.E41005,
                        "payloadHash 与 diagPayload 计算值不符");
            }
        }

        // 8. 自动推导 missPolicy
        String missPolicy = deriveMissPolicy(req.scheduleType(), req.missPolicy());

        // 9. 解析目标范围
        List<String> targetVins = scopeResolver.resolve(
                req.targetScope().type(), toJson(req.targetScope()), tenantId);
        if (targetVins.isEmpty()) {
            throw new BusinessException(ApiError.E41007, "targetScope 解析出的目标车辆数为 0");
        }

        // 10. 生成 taskId 并持久化
        String taskId = "tsk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // 构建 TaskDefinition
        TaskDefinition def = new TaskDefinition();
        def.setTaskId(taskId);
        def.setTenantId(tenantId);
        def.setTaskName(req.taskName());
        def.setVersion(1);
        def.setPriority(req.priority() != null ? req.priority() : 5);
        def.setValidFrom(toLocalDateTime(req.validFrom()));
        def.setValidUntil(toLocalDateTime(req.validUntil()));
        def.setExecuteValidFrom(toLocalDateTime(req.executeValidFrom()));
        def.setExecuteValidUntil(toLocalDateTime(req.executeValidUntil()));
        def.setScheduleType(req.scheduleType());
        def.setScheduleConfig(toJson(req.scheduleConfig()));
        def.setMissPolicy(missPolicy);
        def.setPayloadType(req.payloadType() != null ? req.payloadType() : "batch");
        def.setDiagPayload(toJson(req.diagPayload()));
        def.setPayloadHash(computedHash);
        def.setRequiresApproval(req.requiresApproval() != null && req.requiresApproval());
        def.setStatus(def.getRequiresApproval() ? "pending_approval" : "active");
        def.setCreatedBy(operatorId);

        // 保存 target
        TaskTarget target = new TaskTarget(req.targetScope().type(), toJson(req.targetScope()));
        if (req.targetScope().mode() != null) {
            target.setMode(req.targetScope().mode());
        }
        def.addTarget(target);

        taskDefRepo.save(def);

        // 批量保存 dispatch_record
        String ecuScopeJson = extractEcuScope(req.diagPayload());
        List<TaskDispatchRecord> records = targetVins.stream()
                .map(vin -> new TaskDispatchRecord(taskId, tenantId, vin, ecuScopeJson))
                .toList();
        dispatchRecordRepo.saveAll(records);

        // 架构 §3.3.1: 在同一事务内写 outbox_event,由 Relay Worker 异步投递到 Kafka
        String diagPayloadJson = toJson(req.diagPayload());
        for (TaskDispatchRecord record : records) {
            DispatchCommand cmd = new DispatchCommand(
                    taskId, tenantId, record.getVin(),
                    ecuScopeJson, def.getPriority(),
                    def.getScheduleType(), def.getPayloadType(),
                    diagPayloadJson, computedHash);
            outboxService.enqueueDispatchCommand(tenantId, taskId, cmd);
        }

        log.info("任务创建成功: taskId={}, totalTargets={}, operator={}, outboxEvents={}",
                taskId, targetVins.size(), operatorId, targetVins.size());

        return new TaskController.CreateTaskResponse(taskId, def.getStatus(), targetVins.size(), def.getPayloadType());
    }

    /**
     * 获取单个任务详情。
     */
    @Transactional(readOnly = true)
    public TaskDefinition getTask(String taskId, String tenantId) {
        TaskDefinition def = taskDefRepo.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(ApiError.E40401, "任务不存在: " + taskId));
        if (!def.getTenantId().equals(tenantId)) {
            throw new BusinessException(ApiError.E40401, "任务不存在: " + taskId);
        }
        return def;
    }

    /**
     * 分页查询任务列表。
     */
    @Transactional(readOnly = true)
    public Page<TaskDefinition> listTasks(String tenantId, String status, String scheduleType,
                                          String createdBy, Integer priority, Pageable pageable) {
        return taskDefRepo.findByFilters(tenantId, status, scheduleType, createdBy, priority, pageable);
    }

    /**
     * 暂停任务。
     */
    @Transactional
    public void pauseTask(String taskId, String tenantId) {
        TaskDefinition def = getTask(taskId, tenantId);
        if (!"active".equals(def.getStatus())) {
            throw new BusinessException(ApiError.E40003, "仅 active 状态的任务可暂停,当前: " + def.getStatus());
        }
        def.setStatus("paused");
        taskDefRepo.save(def);
        log.info("任务已暂停: taskId={}", taskId);
    }

    /**
     * 恢复任务。
     */
    @Transactional
    public void resumeTask(String taskId, String tenantId) {
        TaskDefinition def = getTask(taskId, tenantId);
        if (!"paused".equals(def.getStatus())) {
            throw new BusinessException(ApiError.E40003, "仅 paused 状态的任务可恢复,当前: " + def.getStatus());
        }
        def.setStatus("active");
        taskDefRepo.save(def);
        log.info("任务已恢复: taskId={}", taskId);
    }

    /**
     * 取消任务(更新 status 为 canceled)。
     */
    @Transactional
    public void cancelTask(String taskId, String tenantId) {
        TaskDefinition def = getTask(taskId, tenantId);
        if ("completed".equals(def.getStatus()) || "expired".equals(def.getStatus())) {
            throw new BusinessException(ApiError.E40003, "已完成或已过期的任务不可取消");
        }
        def.setStatus("canceled");
        taskDefRepo.save(def);
        log.info("任务已取消: taskId={}", taskId);
    }

    /**
     * 获取任务版本链(沿 supersedes_task_id 追溯)。
     */
    @Transactional(readOnly = true)
    public List<TaskDefinition> getVersionChain(String taskId, String tenantId) {
        // 先验证当前任务存在且属于该租户
        getTask(taskId, tenantId);
        return taskDefRepo.findVersionChain(taskId);
    }

    // ========== 校验方法 ==========

    private void validateCreateRequest(TaskController.CreateTaskRequest req) {
        if (req == null) throw new BusinessException(ApiError.E40001, "请求 body 必填");
        if (req.taskName() == null || req.taskName().isBlank()) {
            throw new BusinessException(ApiError.E40001, "taskName 必填");
        }
        if (req.scheduleType() == null || req.scheduleType().isBlank()) {
            throw new BusinessException(ApiError.E40001, "scheduleType 必填");
        }
        if (req.diagPayload() == null) {
            throw new BusinessException(ApiError.E40001, "diagPayload 必填");
        }
        if (req.targetScope() == null) {
            throw new BusinessException(ApiError.E40001, "targetScope 必填");
        }
        // R2: ecuScope 校验
        String ecuScope = extractEcuScope(req.diagPayload());
        if (ecuScope == null || ecuScope.isBlank() || "[]".equals(ecuScope)) {
            throw new BusinessException(ApiError.E41002, "ecuScope 必填缺失");
        }
    }

    /**
     * R1: maxExecutions=-1 时 executeValidUntil 必填(防永动机)。
     */
    private void validateAntiPerpetual(TaskController.CreateTaskRequest req) {
        if (req.scheduleConfig() == null) return;
        Integer maxExec = extractIntFromJson(req.scheduleConfig(), "maxExecutions");
        if (maxExec != null && maxExec == -1 && req.executeValidUntil() == null) {
            throw new BusinessException(ApiError.E41001,
                    "maxExecutions=-1 时 executeValidUntil 必填(防永动机)");
        }
    }

    /**
     * 校验时间窗口一致性。
     */
    private void validateTimeWindows(TaskController.CreateTaskRequest req) {
        // E41010: executeValidUntil < validUntil
        if (req.executeValidUntil() != null && req.validUntil() != null) {
            if (req.executeValidUntil() < req.validUntil()) {
                throw new BusinessException(ApiError.E41010,
                        "executeValidUntil 不能早于 validUntil");
            }
        }
        // E41011: executeValidFrom < validFrom
        if (req.executeValidFrom() != null && req.validFrom() != null) {
            if (req.executeValidFrom() < req.validFrom()) {
                throw new BusinessException(ApiError.E41011,
                        "executeValidFrom 不能早于 validFrom");
            }
        }
    }

    /**
     * E41004: scheduleType 与 scheduleConfig 一致性校验。
     */
    private void validateScheduleConsistency(TaskController.CreateTaskRequest req) {
        if (req.scheduleConfig() == null) {
            throw new BusinessException(ApiError.E41004, "scheduleConfig 必填");
        }
        switch (req.scheduleType()) {
            case "periodic" -> {
                // periodic 需要 cronExpression 或 intervalMs
                String cron = extractStringFromJson(req.scheduleConfig(), "cronExpression");
                Integer interval = extractIntFromJson(req.scheduleConfig(), "intervalMs");
                if ((cron == null || cron.isBlank()) && (interval == null || interval <= 0)) {
                    throw new BusinessException(ApiError.E41004,
                            "periodic 任务需要 cronExpression 或 intervalMs");
                }
            }
            case "timed" -> {
                // timed 需要 executeAt 或 executeAtList
                Long executeAt = extractLongFromJson(req.scheduleConfig(), "executeAt");
                String executeAtList = extractStringFromJson(req.scheduleConfig(), "executeAtList");
                if (executeAt == null && (executeAtList == null || executeAtList.isBlank())) {
                    throw new BusinessException(ApiError.E41004,
                            "timed 任务需要 executeAt 或 executeAtList");
                }
            }
            case "conditional" -> {
                // conditional 需要 triggerCondition
                String trigger = extractStringFromJson(req.scheduleConfig(), "triggerCondition");
                if (trigger == null || trigger.isBlank() || "null".equals(trigger)) {
                    throw new BusinessException(ApiError.E41004,
                            "conditional 任务需要 triggerCondition");
                }
            }
            case "once" -> {
                // once 不需要额外字段
            }
            default -> throw new BusinessException(ApiError.E41004,
                    "未知的 scheduleType: " + req.scheduleType());
        }
    }

    /**
     * E41006: conditional 任务需要 signalCatalogVersion。
     */
    private void validateConditionalTask(TaskController.CreateTaskRequest req) {
        if (req.scheduleConfig() == null) return;
        Integer version = extractIntFromJson(req.scheduleConfig(), "signalCatalogVersion");
        if (version == null || version <= 0) {
            throw new BusinessException(ApiError.E41006,
                    "conditional 任务需要 signalCatalogVersion");
        }
    }

    // ========== 辅助方法 ==========

    /**
     * 计算 diagPayload 的 SHA-256 hash。
     */
    private String computePayloadHash(Object diagPayload) {
        String json = toJson(diagPayload);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 不可用", e);
        }
    }

    /**
     * 自动推导 missPolicy。
     */
    private String deriveMissPolicy(String scheduleType, String providedPolicy) {
        if (providedPolicy != null && !providedPolicy.isBlank()) {
            return providedPolicy;
        }
        return switch (scheduleType) {
            case "once" -> "fire_once";
            case "periodic" -> "skip_all";
            case "timed" -> "fire_once";
            case "conditional" -> "fire_all";
            default -> "fire_once";
        };
    }

    /**
     * 从 diagPayload JSON 中提取 ecuScope。
     */
    private String extractEcuScope(Object diagPayload) {
        try {
            JsonNode node = objectMapper.valueToTree(diagPayload);
            JsonNode ecuScopeNode = node.get("ecuScope");
            if (ecuScopeNode != null && !ecuScopeNode.isNull()) {
                return ecuScopeNode.toString();
            }
        } catch (Exception e) {
            log.warn("提取 ecuScope 失败", e);
        }
        return null;
    }

    /**
     * 从 diagPayload JSON 中提取 ecuScope 用于 dispatch_record。
     */
    private String extractEcuScopeForDispatch(Object diagPayload) {
        String ecuScope = extractEcuScope(diagPayload);
        return ecuScope != null ? ecuScope : "[]";
    }

    private LocalDateTime toLocalDateTime(Long epochMs) {
        if (epochMs == null) return null;
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ApiError.E40002, "JSON 序列化失败: " + e.getMessage());
        }
    }

    private Integer extractIntFromJson(Object json, String field) {
        try {
            JsonNode node = objectMapper.valueToTree(json);
            JsonNode child = node.get(field);
            if (child != null && child.isNumber()) return child.asInt();
        } catch (Exception ignored) {}
        return null;
    }

    private Long extractLongFromJson(Object json, String field) {
        try {
            JsonNode node = objectMapper.valueToTree(json);
            JsonNode child = node.get(field);
            if (child != null && child.isNumber()) return child.asLong();
        } catch (Exception ignored) {}
        return null;
    }

    private String extractStringFromJson(Object json, String field) {
        try {
            JsonNode node = objectMapper.valueToTree(json);
            JsonNode child = node.get(field);
            if (child != null && !child.isNull()) return child.toString();
        } catch (Exception ignored) {}
        return null;
    }
}

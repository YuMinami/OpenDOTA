package com.opendota.task.revise;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.web.ApiError;
import com.opendota.common.web.BusinessException;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 任务修订服务(REST §6.2、协议 §12.6.3)。
 *
 * <p>核心流程:
 * <ol>
 *   <li>校验旧任务存在性与状态</li>
 *   <li>创建新版本任务(version+1, supersedesTaskId 指向旧任务)</li>
 *   <li>对旧任务的每条活跃 dispatch_record 按 {@link SupersedeDecider} 三分支决策处理</li>
 *   <li>在同一事务内完成所有持久化 + outbox 事件写入</li>
 * </ol>
 */
@Service
public class TaskReviseService {

    private static final Logger log = LoggerFactory.getLogger(TaskReviseService.class);

    private final TaskDefinitionRepository taskDefRepo;
    private final TaskTargetRepository taskTargetRepo;
    private final TaskDispatchRecordRepository dispatchRecordRepo;
    private final SupersedeDecider supersedeDecider;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    public TaskReviseService(TaskDefinitionRepository taskDefRepo,
                             TaskTargetRepository taskTargetRepo,
                             TaskDispatchRecordRepository dispatchRecordRepo,
                             SupersedeDecider supersedeDecider,
                             OutboxService outboxService,
                             ObjectMapper objectMapper) {
        this.taskDefRepo = taskDefRepo;
        this.taskTargetRepo = taskTargetRepo;
        this.dispatchRecordRepo = dispatchRecordRepo;
        this.supersedeDecider = supersedeDecider;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
    }

    /**
     * 修订任务(POST /task/{taskId}/revise)。
     *
     * <p>在同一事务内: 校验 → 创建新版本 → 处理旧任务 dispatch_record → 写 outbox。
     *
     * @param oldTaskId 被修订的旧任务 ID
     * @param req       修订请求(可修改的字段)
     * @param operatorId 操作员 ID
     * @param tenantId  租户 ID
     * @return 新任务的 ID
     */
    @Transactional
    public ReviseTaskResponse reviseTask(String oldTaskId, ReviseTaskRequest req,
                                         String operatorId, String tenantId) {
        // 1. 校验旧任务
        TaskDefinition oldDef = taskDefRepo.findByTaskId(oldTaskId)
                .orElseThrow(() -> new BusinessException(ApiError.E41008,
                        "supersedes 指定的旧任务不存在: " + oldTaskId));
        if (!oldDef.getTenantId().equals(tenantId)) {
            throw new BusinessException(ApiError.E41008,
                    "supersedes 指定的旧任务不在同一租户: " + oldTaskId);
        }
        if ("completed".equals(oldDef.getStatus()) || "expired".equals(oldDef.getStatus())
                || "canceled".equals(oldDef.getStatus())) {
            throw new BusinessException(ApiError.E40003,
                    "旧任务已终态(status=" + oldDef.getStatus() + "),不可修订");
        }

        // 2. 构建新版本任务定义
        TaskDefinition newDef = buildNewVersion(oldDef, req, operatorId);
        taskDefRepo.save(newDef);

        // 3. 复制 target
        List<TaskTarget> oldTargets = taskTargetRepo.findByTaskDefinition_TaskId(oldTaskId);
        for (TaskTarget oldTarget : oldTargets) {
            TaskTarget newTarget = new TaskTarget(oldTarget.getTargetType(), oldTarget.getTargetValue());
            newDef.addTarget(newTarget);
        }

        // 4. 处理旧任务的活跃 dispatch_record
        List<TaskDispatchRecord> oldRecords = dispatchRecordRepo.findActiveByTaskId(oldTaskId);
        String oldDiagPayload = oldDef.getDiagPayload();

        int directReplaceCount = 0;
        int supersedingCount = 0;
        int rejectCount = 0;
        int skipCount = 0;
        List<String> rejectedVins = new ArrayList<>();

        String newEcuScope = extractEcuScope(newDef.getDiagPayload());
        String newDiagPayloadJson = newDef.getDiagPayload();
        String newPayloadHash = newDef.getPayloadHash();

        for (TaskDispatchRecord oldRecord : oldRecords) {
            SupersedeDecider.SupersedeDecision decision = supersedeDecider.decide(oldRecord, oldDiagPayload);

            switch (decision.action()) {
                case DIRECT_REPLACE -> {
                    // 旧记录标记为 canceled + supersededBy
                    oldRecord.setDispatchStatus("canceled");
                    oldRecord.setSupersededBy(newDef.getTaskId());
                    dispatchRecordRepo.save(oldRecord);

                    // 新记录 + outbox
                    createNewDispatchAndEnqueue(newDef, oldRecord.getVin(), newEcuScope,
                            newDiagPayloadJson, newPayloadHash);
                    directReplaceCount++;
                }
                case MARK_SUPERSEDING -> {
                    // 旧记录标记为 superseding(等待车端确认)
                    oldRecord.setDispatchStatus("superseding");
                    oldRecord.setSupersededBy(newDef.getTaskId());
                    dispatchRecordRepo.save(oldRecord);

                    // 新记录 + outbox(带 supersedes 字段)
                    createNewDispatchAndEnqueueSupersedes(newDef, oldTaskId, oldRecord.getVin(),
                            newEcuScope, newDiagPayloadJson, newPayloadHash);
                    supersedingCount++;
                }
                case REJECT -> {
                    // 不可中断:新任务的 dispatch_record 标记为 pending_backlog
                    oldRecord.setSupersededBy(newDef.getTaskId());
                    dispatchRecordRepo.save(oldRecord);

                    TaskDispatchRecord newRecord = new TaskDispatchRecord(
                            newDef.getTaskId(), tenantId, oldRecord.getVin(), newEcuScope);
                    newRecord.setDispatchStatus("pending_backlog");
                    newRecord.setLastError(decision.rejectReason());
                    dispatchRecordRepo.save(newRecord);

                    rejectedVins.add(oldRecord.getVin());
                    rejectCount++;
                }
                case SKIP -> {
                    skipCount++;
                }
            }
        }

        // 5. 更新旧任务状态为 canceled
        oldDef.setStatus("canceled");
        taskDefRepo.save(oldDef);

        log.info("任务修订完成: oldTaskId={}, newTaskId={}, version={}, 操作员={}, " +
                 "directReplace={}, superseding={}, reject={}, skip={}",
                oldTaskId, newDef.getTaskId(), newDef.getVersion(), operatorId,
                directReplaceCount, supersedingCount, rejectCount, skipCount);

        return new ReviseTaskResponse(
                newDef.getTaskId(),
                newDef.getStatus(),
                newDef.getVersion(),
                oldTaskId,
                directReplaceCount + supersedingCount,
                rejectCount,
                rejectedVins);
    }

    /**
     * 构建新版本的任务定义。
     */
    private TaskDefinition buildNewVersion(TaskDefinition oldDef, ReviseTaskRequest req, String operatorId) {
        TaskDefinition newDef = new TaskDefinition();
        newDef.setTaskId("tsk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        newDef.setTenantId(oldDef.getTenantId());
        newDef.setTaskName(req.taskName() != null ? req.taskName() : oldDef.getTaskName());
        newDef.setVersion(oldDef.getVersion() + 1);
        newDef.setSupersedesTaskId(oldDef.getTaskId());
        newDef.setPriority(req.priority() != null ? req.priority() : oldDef.getPriority());
        newDef.setValidFrom(oldDef.getValidFrom());
        newDef.setValidUntil(oldDef.getValidUntil());
        newDef.setExecuteValidFrom(oldDef.getExecuteValidFrom());
        newDef.setExecuteValidUntil(oldDef.getExecuteValidUntil());
        newDef.setScheduleType(oldDef.getScheduleType());
        newDef.setScheduleConfig(req.scheduleConfig() != null ? toJson(req.scheduleConfig()) : oldDef.getScheduleConfig());
        newDef.setMissPolicy(oldDef.getMissPolicy());
        newDef.setPayloadType(oldDef.getPayloadType());
        newDef.setDiagPayload(req.diagPayload() != null ? toJson(req.diagPayload()) : oldDef.getDiagPayload());
        newDef.setPayloadHash(computePayloadHash(newDef.getDiagPayload()));
        newDef.setRequiresApproval(oldDef.getRequiresApproval());
        newDef.setStatus("active");
        newDef.setCreatedBy(operatorId);
        return newDef;
    }

    /**
     * 创建新 dispatch_record 并写 outbox 事件(常规,无 supersedes)。
     */
    private void createNewDispatchAndEnqueue(TaskDefinition newDef, String vin,
                                              String ecuScope, String diagPayloadJson, String payloadHash) {
        TaskDispatchRecord newRecord = new TaskDispatchRecord(
                newDef.getTaskId(), newDef.getTenantId(), vin, ecuScope);
        dispatchRecordRepo.save(newRecord);

        DispatchCommand cmd = new DispatchCommand(
                newDef.getTaskId(), newDef.getTenantId(), vin,
                ecuScope, newDef.getPriority(),
                newDef.getScheduleType(), newDef.getPayloadType(),
                diagPayloadJson, payloadHash);
        outboxService.enqueueDispatchCommand(newDef.getTenantId(), newDef.getTaskId(), cmd);
    }

    /**
     * 创建新 dispatch_record 并写 outbox 事件(带 supersedes 字段)。
     */
    private void createNewDispatchAndEnqueueSupersedes(TaskDefinition newDef, String oldTaskId,
                                                        String vin, String ecuScope,
                                                        String diagPayloadJson, String payloadHash) {
        TaskDispatchRecord newRecord = new TaskDispatchRecord(
                newDef.getTaskId(), newDef.getTenantId(), vin, ecuScope);
        dispatchRecordRepo.save(newRecord);

        DispatchCommand cmd = new DispatchCommand(
                newDef.getTaskId(), newDef.getTenantId(), vin,
                ecuScope, newDef.getPriority(),
                newDef.getScheduleType(), newDef.getPayloadType(),
                diagPayloadJson, payloadHash, oldTaskId);
        outboxService.enqueueDispatchCommand(newDef.getTenantId(), newDef.getTaskId(), cmd);
    }

    private String computePayloadHash(String diagPayloadJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(diagPayloadJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 不可用", e);
        }
    }

    private String extractEcuScope(String diagPayloadJson) {
        if (diagPayloadJson == null) return "[]";
        try {
            JsonNode node = objectMapper.readTree(diagPayloadJson);
            JsonNode ecuScope = node.get("ecuScope");
            if (ecuScope != null && !ecuScope.isNull()) {
                return ecuScope.toString();
            }
        } catch (Exception e) {
            // ignore
        }
        return "[]";
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

    // ========== DTO ==========

    /**
     * POST /api/task/{taskId}/revise 请求体(REST §6.2)。
     * 所有字段可选,仅传入需要修改的字段。
     */
    public record ReviseTaskRequest(
            String taskName,
            Integer priority,
            Object scheduleConfig,
            Object diagPayload
    ) {}

    /**
     * 修订响应。
     */
    public record ReviseTaskResponse(
            String taskId,
            String status,
            int version,
            String supersedesTaskId,
            int dispatchedCount,
            int rejectedCount,
            List<String> rejectedVins
    ) {}
}

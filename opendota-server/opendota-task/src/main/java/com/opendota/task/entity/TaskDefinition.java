package com.opendota.task.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务定义实体，对应 task_definition 表。
 * 参考: REST §6、协议 §8.2
 */
@Entity
@Table(name = "task_definition")
public class TaskDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, unique = true, length = 64)
    private String taskId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "task_name", nullable = false, length = 256)
    private String taskName;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "supersedes_task_id", length = 64)
    private String supersedesTaskId;

    @Column
    private Integer priority = 5;

    @Column(name = "valid_from")
    private LocalDateTime validFrom;

    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    @Column(name = "execute_valid_from")
    private LocalDateTime executeValidFrom;

    @Column(name = "execute_valid_until")
    private LocalDateTime executeValidUntil;

    @Column(name = "schedule_type", nullable = false, length = 32)
    private String scheduleType;

    @Column(name = "schedule_config", nullable = false, columnDefinition = "jsonb")
    private String scheduleConfig;

    @Column(name = "miss_policy", length = 32)
    private String missPolicy;

    @Column(name = "payload_type", length = 32)
    private String payloadType;

    @Column(name = "diag_payload", nullable = false, columnDefinition = "jsonb")
    private String diagPayload;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "requires_approval")
    private Boolean requiresApproval = false;

    @Column(name = "approval_id", length = 64)
    private String approvalId;

    @Column(length = 32)
    private String status;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "taskDefinition", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<TaskTarget> targets = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void addTarget(TaskTarget target) {
        targets.add(target);
        target.setTaskDefinition(this);
    }

    // --- Getters & Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getSupersedesTaskId() { return supersedesTaskId; }
    public void setSupersedesTaskId(String supersedesTaskId) { this.supersedesTaskId = supersedesTaskId; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public LocalDateTime getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDateTime validFrom) { this.validFrom = validFrom; }

    public LocalDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDateTime validUntil) { this.validUntil = validUntil; }

    public LocalDateTime getExecuteValidFrom() { return executeValidFrom; }
    public void setExecuteValidFrom(LocalDateTime executeValidFrom) { this.executeValidFrom = executeValidFrom; }

    public LocalDateTime getExecuteValidUntil() { return executeValidUntil; }
    public void setExecuteValidUntil(LocalDateTime executeValidUntil) { this.executeValidUntil = executeValidUntil; }

    public String getScheduleType() { return scheduleType; }
    public void setScheduleType(String scheduleType) { this.scheduleType = scheduleType; }

    public String getScheduleConfig() { return scheduleConfig; }
    public void setScheduleConfig(String scheduleConfig) { this.scheduleConfig = scheduleConfig; }

    public String getMissPolicy() { return missPolicy; }
    public void setMissPolicy(String missPolicy) { this.missPolicy = missPolicy; }

    public String getPayloadType() { return payloadType; }
    public void setPayloadType(String payloadType) { this.payloadType = payloadType; }

    public String getDiagPayload() { return diagPayload; }
    public void setDiagPayload(String diagPayload) { this.diagPayload = diagPayload; }

    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }

    public Boolean getRequiresApproval() { return requiresApproval; }
    public void setRequiresApproval(Boolean requiresApproval) { this.requiresApproval = requiresApproval; }

    public String getApprovalId() { return approvalId; }
    public void setApprovalId(String approvalId) { this.approvalId = approvalId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public List<TaskTarget> getTargets() { return targets; }
    public void setTargets(List<TaskTarget> targets) { this.targets = targets; }
}

package com.opendota.task.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * 任务分发记录实体，对应 task_dispatch_record 表。
 * 每辆车一行，跟踪任务在单个车辆上的分发状态。
 */
@Entity
@Table(name = "task_dispatch_record",
       uniqueConstraints = @UniqueConstraint(columnNames = {"task_id", "vin"}))
public class TaskDispatchRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 17)
    private String vin;

    @Column(name = "ecu_scope", columnDefinition = "jsonb")
    private String ecuScope;

    @Column(name = "dispatch_status", length = 32)
    private String dispatchStatus = "pending_online";

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "result_payload", columnDefinition = "jsonb")
    private String resultPayload;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "last_error", length = 128)
    private String lastError;

    @Column(name = "superseded_by", length = 64)
    private String supersededBy;

    @Column(name = "current_execution_count")
    private Integer currentExecutionCount = 0;

    @Column(name = "last_reported_at")
    private LocalDateTime lastReportedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public TaskDispatchRecord() {}

    public TaskDispatchRecord(String taskId, String tenantId, String vin, String ecuScope) {
        this.taskId = taskId;
        this.tenantId = tenantId;
        this.vin = vin;
        this.ecuScope = ecuScope;
        this.dispatchStatus = "pending_online";
    }

    // --- Getters & Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getVin() { return vin; }
    public void setVin(String vin) { this.vin = vin; }

    public String getEcuScope() { return ecuScope; }
    public void setEcuScope(String ecuScope) { this.ecuScope = ecuScope; }

    public String getDispatchStatus() { return dispatchStatus; }
    public void setDispatchStatus(String dispatchStatus) { this.dispatchStatus = dispatchStatus; }

    public LocalDateTime getDispatchedAt() { return dispatchedAt; }
    public void setDispatchedAt(LocalDateTime dispatchedAt) { this.dispatchedAt = dispatchedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public String getResultPayload() { return resultPayload; }
    public void setResultPayload(String resultPayload) { this.resultPayload = resultPayload; }

    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public String getSupersededBy() { return supersededBy; }
    public void setSupersededBy(String supersededBy) { this.supersededBy = supersededBy; }

    public Integer getCurrentExecutionCount() { return currentExecutionCount; }
    public void setCurrentExecutionCount(Integer currentExecutionCount) { this.currentExecutionCount = currentExecutionCount; }

    public LocalDateTime getLastReportedAt() { return lastReportedAt; }
    public void setLastReportedAt(LocalDateTime lastReportedAt) { this.lastReportedAt = lastReportedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}

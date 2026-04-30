package com.opendota.task.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 任务目标实体，对应 task_target 表。
 * 记录任务的目标范围（VIN 列表/车型/标签/全部）。
 *
 * <p>V7 新增字段:
 * <ul>
 *   <li>{@code mode} — snapshot(默认,创建即冻结) / dynamic(周期扫描吸纳新车)</li>
 *   <li>{@code dynamicLastResolvedAt} — DynamicTargetScopeWorker 上次扫描时刻,增量锚点</li>
 *   <li>{@code dynamicResolutionCount} — 累计扫描次数</li>
 * </ul>
 */
@Entity
@Table(name = "task_target")
public class TaskTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", referencedColumnName = "task_id", nullable = false)
    @JsonIgnore
    private TaskDefinition taskDefinition;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "target_value", nullable = false, columnDefinition = "jsonb")
    private String targetValue;

    /** 目标范围模式: snapshot(默认) / dynamic(V7 新增) */
    @Column(nullable = false, length = 16)
    private String mode = "snapshot";

    /** DynamicTargetScopeWorker 上次扫描时刻,增量解析锚点 */
    @Column(name = "dynamic_last_resolved_at")
    private LocalDateTime dynamicLastResolvedAt;

    /** 累计扫描次数,供 Grafana 面板展示 */
    @Column(name = "dynamic_resolution_count", nullable = false)
    private Integer dynamicResolutionCount = 0;

    public TaskTarget() {}

    public TaskTarget(String targetType, String targetValue) {
        this.targetType = targetType;
        this.targetValue = targetValue;
        this.mode = "snapshot";
    }

    // --- Getters & Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public TaskDefinition getTaskDefinition() { return taskDefinition; }
    public void setTaskDefinition(TaskDefinition taskDefinition) { this.taskDefinition = taskDefinition; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public String getTargetValue() { return targetValue; }
    public void setTargetValue(String targetValue) { this.targetValue = targetValue; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public LocalDateTime getDynamicLastResolvedAt() { return dynamicLastResolvedAt; }
    public void setDynamicLastResolvedAt(LocalDateTime dynamicLastResolvedAt) { this.dynamicLastResolvedAt = dynamicLastResolvedAt; }

    public Integer getDynamicResolutionCount() { return dynamicResolutionCount; }
    public void setDynamicResolutionCount(Integer dynamicResolutionCount) { this.dynamicResolutionCount = dynamicResolutionCount; }
}

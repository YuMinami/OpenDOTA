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

/**
 * 任务目标实体，对应 task_target 表。
 * 记录任务的目标范围（VIN 列表/车型/标签/全部）。
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

    public TaskTarget() {}

    public TaskTarget(String targetType, String targetValue) {
        this.targetType = targetType;
        this.targetValue = targetValue;
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
}

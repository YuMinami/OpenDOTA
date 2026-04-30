package com.opendota.task.repository;

import com.opendota.task.entity.TaskTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务目标仓库。
 */
@Repository
public interface TaskTargetRepository extends JpaRepository<TaskTarget, Long> {

    List<TaskTarget> findByTaskDefinition_TaskId(String taskId);

    void deleteByTaskDefinition_TaskId(String taskId);

    /**
     * 按主键列表批量加载(用于 DynamicTargetScopeWorker 从 JDBC 扫描结果加载实体)。
     */
    List<TaskTarget> findByIdIn(List<Long> ids);

    /**
     * 更新 dynamic 目标的扫描元数据。
     */
    @Modifying
    @Query("UPDATE TaskTarget t SET t.dynamicLastResolvedAt = :now, " +
           "t.dynamicResolutionCount = t.dynamicResolutionCount + 1 WHERE t.id = :id")
    void updateDynamicScanMetadata(@Param("id") Long id, @Param("now") LocalDateTime now);
}

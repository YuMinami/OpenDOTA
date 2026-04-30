package com.opendota.task.repository;

import com.opendota.task.entity.TaskDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 任务定义仓库。
 */
@Repository
public interface TaskDefinitionRepository extends JpaRepository<TaskDefinition, Long> {

    Optional<TaskDefinition> findByTaskId(String taskId);

    Page<TaskDefinition> findByTenantId(String tenantId, Pageable pageable);

    @Query("SELECT t FROM TaskDefinition t WHERE t.tenantId = :tenantId " +
           "AND (:status IS NULL OR t.status = :status) " +
           "AND (:scheduleType IS NULL OR t.scheduleType = :scheduleType) " +
           "AND (:createdBy IS NULL OR t.createdBy = :createdBy) " +
           "AND (:priority IS NULL OR t.priority = :priority)")
    Page<TaskDefinition> findByFilters(
            @Param("tenantId") String tenantId,
            @Param("status") String status,
            @Param("scheduleType") String scheduleType,
            @Param("createdBy") String createdBy,
            @Param("priority") Integer priority,
            Pageable pageable);

    List<TaskDefinition> findBySupersedesTaskIdOrderByVersionDesc(String supersedesTaskId);

    @Query("SELECT t FROM TaskDefinition t WHERE t.taskId = :taskId OR t.supersedesTaskId = :taskId ORDER BY t.version ASC")
    List<TaskDefinition> findVersionChain(@Param("taskId") String taskId);

    boolean existsByTaskIdAndTenantId(String taskId, String tenantId);
}

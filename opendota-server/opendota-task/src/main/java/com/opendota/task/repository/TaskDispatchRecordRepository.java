package com.opendota.task.repository;

import com.opendota.task.entity.TaskDispatchRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 任务分发记录仓库。
 */
@Repository
public interface TaskDispatchRecordRepository extends JpaRepository<TaskDispatchRecord, Long> {

    long countByTaskId(String taskId);

    Optional<TaskDispatchRecord> findByTaskIdAndVin(String taskId, String vin);

    Page<TaskDispatchRecord> findByTaskId(String taskId, Pageable pageable);

    @Query("SELECT d.dispatchStatus, COUNT(d) FROM TaskDispatchRecord d " +
           "WHERE d.taskId = :taskId GROUP BY d.dispatchStatus")
    List<Object[]> countByStatusGrouped(@Param("taskId") String taskId);

    /**
     * 查询指定任务 ID 列表下的所有分发记录。
     */
    List<TaskDispatchRecord> findByTaskIdIn(List<String> taskIds);

    /**
     * 查询指定任务下非终态的分发记录(用于 supersede 场景)。
     */
    @Query("SELECT d FROM TaskDispatchRecord d WHERE d.taskId = :taskId " +
           "AND d.dispatchStatus NOT IN ('completed', 'canceled', 'expired')")
    List<TaskDispatchRecord> findActiveByTaskId(@Param("taskId") String taskId);
}

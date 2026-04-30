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
}

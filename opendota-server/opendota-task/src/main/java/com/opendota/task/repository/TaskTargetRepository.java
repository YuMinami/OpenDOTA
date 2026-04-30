package com.opendota.task.repository;

import com.opendota.task.entity.TaskTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 任务目标仓库。
 */
@Repository
public interface TaskTargetRepository extends JpaRepository<TaskTarget, Long> {

    List<TaskTarget> findByTaskDefinition_TaskId(String taskId);

    void deleteByTaskDefinition_TaskId(String taskId);
}

-- OpenDOTA Flyway V9 迁移
-- 新增: v_task_board_progress 视图(任务看板进度聚合)
-- 引入日期: 2026-04-30
--
-- 背景:
--   Phase 4 Step 4.8 进度聚合 API(GET /api/task/{taskId}/progress)需要
--   `boardStatus` 字段(in_progress / all_completed / partial_completed / all_failed),
--   该字段不在数据库存量列上,由 task_dispatch_record.dispatch_status 在 task 维度聚合得出。
--
-- 设计来源: 架构文档 §4.8.1 task_definition.status 终态判定规则(v1.2)
--   - 4 个终态: completed / failed / canceled / expired
--   - partial_completed 仅看板视图存在,不落库到 task_definition.status
--
-- 性能注记:
--   - 当前以普通 VIEW 实现,实时 GROUP BY task_dispatch_record。索引 idx_dispatch_record(task_id) 已存在。
--   - 任务规模 5000 目标/任务 仍可接受(单 task 全表扫描),若后续看板 QPS 升高
--     可改为物化视图 + 触发器或 worker 增量刷新(待 Phase 4 DoD 复盘后决策)。
--
-- 用法:
--   SELECT * FROM v_task_board_progress WHERE task_id = ?;

CREATE OR REPLACE VIEW v_task_board_progress AS
SELECT
    task_id,
    SUM(CASE WHEN dispatch_status = 'completed' THEN 1 ELSE 0 END)::INT AS completed_cnt,
    SUM(CASE WHEN dispatch_status = 'failed'    THEN 1 ELSE 0 END)::INT AS failed_cnt,
    SUM(CASE WHEN dispatch_status = 'expired'   THEN 1 ELSE 0 END)::INT AS expired_cnt,
    SUM(CASE WHEN dispatch_status = 'canceled'  THEN 1 ELSE 0 END)::INT AS canceled_cnt,
    COUNT(*)::INT AS total_cnt,
    CASE
        WHEN SUM(CASE WHEN dispatch_status NOT IN ('completed','failed','canceled','expired') THEN 1 ELSE 0 END) > 0
             THEN 'in_progress'
        WHEN SUM(CASE WHEN dispatch_status = 'completed' THEN 1 ELSE 0 END) = COUNT(*)
             THEN 'all_completed'
        WHEN SUM(CASE WHEN dispatch_status = 'completed' THEN 1 ELSE 0 END) > 0
             THEN 'partial_completed'
        ELSE 'all_failed'
    END AS board_status
FROM task_dispatch_record
GROUP BY task_id;

COMMENT ON VIEW v_task_board_progress IS
    'v1.2 架构 §4.8.1: 任务看板进度聚合,GET /api/task/{taskId}/progress 取 board_status';

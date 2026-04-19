-- OpenDOTA Flyway V7 迁移
-- 新增: task_target.mode 字段 + 动态解析支持(v1.4 审阅 A3)
-- 引入日期: 2026-04-19
--
-- 背景:
--   v1.2 基线 task_target 只记录目标范围定义(vin_list / model / tag / all),
--   未约定"新下线的车是否自动纳入活跃的周期/条件任务"。
--
--   v1.4 决议:双模式
--     - snapshot(默认): POST /task 时立即解析冻结为 N 条 dispatch_record,新车不纳入
--     - dynamic: 定时 Worker 周期扫描 task_target,对新匹配但无 dispatch_record 的 VIN 补发
--
-- 配合:
--   - 架构 §4.x DynamicTargetScopeWorker:每 1 小时扫描 mode='dynamic' 且 task 状态为 active 的目标
--   - 首次扫描时 dynamic_last_resolved_at 为空,视为全量补发(等同 snapshot);后续 incremental
--   - 任务终态(completed/expired/canceled)后 Worker 跳过

ALTER TABLE task_target
    ADD COLUMN IF NOT EXISTS mode VARCHAR(16) NOT NULL DEFAULT 'snapshot'
        CHECK (mode IN ('snapshot', 'dynamic'));

ALTER TABLE task_target
    ADD COLUMN IF NOT EXISTS dynamic_last_resolved_at TIMESTAMP;

ALTER TABLE task_target
    ADD COLUMN IF NOT EXISTS dynamic_resolution_count INT NOT NULL DEFAULT 0;

-- Worker 主扫描:只关心 dynamic 模式
CREATE INDEX IF NOT EXISTS idx_task_target_dynamic_scan
    ON task_target(mode, dynamic_last_resolved_at NULLS FIRST)
    WHERE mode = 'dynamic';

COMMENT ON COLUMN task_target.mode IS
    'v1.4 新增:snapshot(创建即冻结)/ dynamic(周期任务定时吸纳新车)';
COMMENT ON COLUMN task_target.dynamic_last_resolved_at IS
    'v1.4:DynamicTargetScopeWorker 上次扫描时刻,增量解析锚点';
COMMENT ON COLUMN task_target.dynamic_resolution_count IS
    'v1.4:累计扫描次数,供可观测性 Grafana 面板展示';

-- 约束:仅 schedule_type 为 periodic/conditional 的任务允许 dynamic 模式
-- 这是业务规则,不在表级 CHECK(需跨表),由 API 层校验(§6.1 POST /task)
-- 数据库端作为额外防护,用触发器兜底:
CREATE OR REPLACE FUNCTION fn_assert_dynamic_mode_eligible()
RETURNS TRIGGER AS $$
DECLARE
    sched_type VARCHAR(32);
BEGIN
    IF NEW.mode = 'dynamic' THEN
        SELECT schedule_type INTO sched_type
        FROM task_definition
        WHERE task_id = NEW.task_id;

        IF sched_type NOT IN ('periodic', 'conditional') THEN
            RAISE EXCEPTION
                'task_target.mode=dynamic is only allowed for periodic/conditional tasks (task_id=%, schedule_type=%)',
                NEW.task_id, sched_type
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_task_target_dynamic_eligible ON task_target;
CREATE TRIGGER trg_task_target_dynamic_eligible
    BEFORE INSERT OR UPDATE OF mode ON task_target
    FOR EACH ROW
    EXECUTE FUNCTION fn_assert_dynamic_mode_eligible();

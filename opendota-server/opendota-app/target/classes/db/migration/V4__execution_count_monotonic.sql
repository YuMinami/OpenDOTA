-- OpenDOTA Flyway V4 迁移
-- 加固: task_dispatch_record.current_execution_count + task_definition.version 单调不减
-- 引入日期: 2026-04-19(审阅闭环 P1-9)
--
-- 背景:
--   协议 §8.5.1 规定云端对周期任务计数使用 GREATEST(旧值, execution_seq) 更新,
--   但应用层 GREATEST 在 read-modify-write 并发下仍可能回退(两节点读同值 → 各自写不同新值 → 后写的胜出)。
--   本触发器作为数据库层兜底,拒绝任何使计数减少的 UPDATE。

CREATE OR REPLACE FUNCTION fn_assert_execution_count_monotonic()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.current_execution_count < OLD.current_execution_count THEN
        RAISE EXCEPTION
            'current_execution_count cannot decrease: task_id=% vin=% old=% new=%',
            OLD.task_id, OLD.vin, OLD.current_execution_count, NEW.current_execution_count
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_dispatch_exec_count_monotonic ON task_dispatch_record;
CREATE TRIGGER trg_dispatch_exec_count_monotonic
    BEFORE UPDATE OF current_execution_count ON task_dispatch_record
    FOR EACH ROW
    WHEN (NEW.current_execution_count IS DISTINCT FROM OLD.current_execution_count)
    EXECUTE FUNCTION fn_assert_execution_count_monotonic();

-- 同理保护 task_definition.version(supersedes 链),避免误改导致版本倒退
CREATE OR REPLACE FUNCTION fn_assert_task_version_monotonic()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.version < OLD.version THEN
        RAISE EXCEPTION
            'task_definition.version cannot decrease: task_id=% old=% new=%',
            OLD.task_id, OLD.version, NEW.version
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_task_def_version_monotonic ON task_definition;
CREATE TRIGGER trg_task_def_version_monotonic
    BEFORE UPDATE OF version ON task_definition
    FOR EACH ROW
    WHEN (NEW.version IS DISTINCT FROM OLD.version)
    EXECUTE FUNCTION fn_assert_task_version_monotonic();

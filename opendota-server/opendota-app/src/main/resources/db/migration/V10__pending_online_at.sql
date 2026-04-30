-- OpenDOTA Flyway V10 迁移
-- 新增: task_dispatch_record.pending_online_at 字段
-- 引入日期: 2026-04-30(Phase 4 DoD 收口)
--
-- 背景:
--   Phase 4 DoD 第 3 条要求 offline_task_push_latency P95 < 10s 指标可观测。
--   该指标定义为"分发记录从 dispatch_status='pending_online' 写入到首次成功 dispatched
--   的端到端延迟"。需要一个时间戳列作为基准点,本字段在 DispatchCommandListener
--   markPendingOnline 时写入,在 PendingTaskReplayService 回放时读取计算 latency
--   并清空(避免二次回放被重复采样)。
--
-- 设计来源: 架构 §4.5 离线任务回放 + §9.2 SLI 指标体系
--
-- 兼容性:
--   - 字段允许 NULL,旧记录(本迁移之前已为 pending_online)首次回放时 latency 为 NULL,
--     从指标采样中跳过(不影响 P95 准确性,只是初次发布后短窗口内样本数偏少)。
--   - 不加索引:仅 replayPendingTasks 内联读取,VIN+status 已有索引。

ALTER TABLE task_dispatch_record
    ADD COLUMN IF NOT EXISTS pending_online_at TIMESTAMP;

COMMENT ON COLUMN task_dispatch_record.pending_online_at IS
    'Phase 4 DoD: 首次进入 pending_online 状态的时间戳,offline_task_push_latency 指标的基准点';

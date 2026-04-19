-- OpenDOTA Flyway V5 迁移
-- 新增: outbox_event 表(架构 §3.3.1 Transactional Outbox Pattern)
-- 引入日期: 2026-04-19(v1.4 审阅闭环)
--
-- 背景:
--   架构 §3.3.1 要求任务创建时 PG 事务与 Kafka 投递必须通过 outbox 表解耦,
--   避免"PG 提交成功但 Kafka 失败"或反之的不一致。OutboxRelayWorker 在独立事务中
--   扫描 status='new'/'failed' 的记录投递到 Kafka,成功后置 sent。
--   v1.2 基线 DDL 声明了该需求但 V1 迁移未包含建表,本迁移补齐。
--
-- 配合:
--   - OutboxRelayWorker(opendota-task 模块)负责扫描投递
--   - FOR UPDATE SKIP LOCKED 保证多节点并发消费不冲突
--   - 指数退避:next_retry_at = now() + min(attempts,8)*30s
--   - 7 天后由 OutboxArchiveJob 搬冷表

CREATE TABLE IF NOT EXISTS outbox_event (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    aggregate_type  VARCHAR(32) NOT NULL,              -- task_definition / execution / dispatch / ...
    aggregate_id    VARCHAR(64) NOT NULL,
    event_type      VARCHAR(64) NOT NULL,              -- DispatchCommand / TaskRevised / ...
    payload         JSONB NOT NULL,
    kafka_topic     VARCHAR(128) NOT NULL,             -- 目标 topic,Relay 按此路由,避免硬编码
    kafka_key       VARCHAR(128),                      -- 分区键,默认 aggregate_id
    status          VARCHAR(16) DEFAULT 'new'
        CHECK (status IN ('new','sent','failed','archived')),
    attempts        INT DEFAULT 0,
    next_retry_at   TIMESTAMP,
    last_error      VARCHAR(512),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    sent_at         TIMESTAMP
);

-- Relay 扫描主索引:待处理(new / 已过重试窗口的 failed)
CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON outbox_event(next_retry_at NULLS FIRST, id)
    WHERE status IN ('new', 'failed');

-- 监控/审计查询
CREATE INDEX IF NOT EXISTS idx_outbox_tenant_status
    ON outbox_event(tenant_id, status, created_at DESC);

-- 冷归档/GC 依据
CREATE INDEX IF NOT EXISTS idx_outbox_sent_at
    ON outbox_event(sent_at)
    WHERE status = 'sent';

-- 多租户隔离
ALTER TABLE outbox_event ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_tenant_outbox ON outbox_event;
CREATE POLICY rls_tenant_outbox ON outbox_event
    USING (tenant_id = current_setting('app.tenant_id', true));

-- Relay Worker 需要绕过 RLS(跨租户扫描),使用独立数据库角色
-- 生产环境由 DBA 创建:
--   CREATE ROLE outbox_relay BYPASSRLS;
--   GRANT SELECT, UPDATE ON outbox_event TO outbox_relay;
-- 应用连接池按用途切换:业务 RLS 账号 vs Relay BYPASSRLS 账号

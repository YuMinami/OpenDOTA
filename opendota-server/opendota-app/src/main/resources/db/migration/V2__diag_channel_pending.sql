-- OpenDOTA Flyway V2 迁移
-- 新增: diag_channel_pending 表(架构 §4.3.2)
-- 目的: preemptPolicy=wait 时云端持久化 channel_open pending,确保收到 channel_ready 后跨节点 replay
-- 引入日期: 2026-04-19(审阅闭环)

CREATE TABLE IF NOT EXISTS diag_channel_pending (
    id               BIGSERIAL PRIMARY KEY,
    channel_id       VARCHAR(64) NOT NULL UNIQUE,
    tenant_id        VARCHAR(64) NOT NULL,
    vin              VARCHAR(17) NOT NULL,
    ecu_scope        JSONB NOT NULL,
    operator_id      VARCHAR(64) NOT NULL,
    original_request JSONB NOT NULL,
    rejected_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    conflict_task_id VARCHAR(64),
    expires_at       TIMESTAMP NOT NULL,
    status           VARCHAR(16) DEFAULT 'waiting'
        CHECK (status IN ('waiting','replayed','timeout','canceled')),
    replayed_at      TIMESTAMP,
    sse_client_id    VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_dcp_vin_status ON diag_channel_pending(vin, status)
    WHERE status = 'waiting';
CREATE INDEX IF NOT EXISTS idx_dcp_expires ON diag_channel_pending(expires_at)
    WHERE status = 'waiting';

ALTER TABLE diag_channel_pending ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_tenant_dcp ON diag_channel_pending;
CREATE POLICY rls_tenant_dcp ON diag_channel_pending
    USING (tenant_id = current_setting('app.tenant_id', true));

-- OpenDOTA PostgreSQL Flyway Baseline 迁移
-- 版本: V1 = v1.2 文档 baseline(企业级加固: ECU 级互斥 / 周期计数一致性 / 聚合分片 / 时钟信任 / 信号目录版本化)
-- 覆盖:
--   1. ODX 配置表(车型 / ECU / 诊断服务 / 参数编解码)
--   2. 诊断记录 + 审计链外键
--   3. 租户 / 操作者 / 角色 / 审批(Maker-Checker)
--   4. 任务管理(定义 / 目标 / 分发 / 执行日志 / 候补队列 / 分片 / 时钟信任)
--   5. 在线状态 / 通道事件 / 条件触发日志 / 固件会话 / SSE 流水 / 安全审计
--   6. 行级安全(RLS)多租户隔离
-- Mock 数据见 R__mock_seed.sql(Repeatable,仅 dev/staging 环境执行,prod 禁用)

-- ==========================================
-- 1. 扩展与基础字典
-- ==========================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ==========================================
-- 2. 租户与身份权限
-- ==========================================

-- 角色字典(只读)
CREATE TABLE IF NOT EXISTS role (
    id                SERIAL PRIMARY KEY,
    role_name         VARCHAR(32) NOT NULL UNIQUE,
    description       TEXT
);

-- 操作者主表(审计链起点,离职只做 status=disabled,禁止物理删除)
CREATE TABLE IF NOT EXISTS operator (
    id                VARCHAR(64) PRIMARY KEY,
    tenant_id         VARCHAR(64) NOT NULL,
    display_name      VARCHAR(128) NOT NULL,
    email             VARCHAR(256),
    phone             VARCHAR(32),
    status            VARCHAR(16) DEFAULT 'active',        -- active / disabled / locked
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login_at     TIMESTAMP,
    disabled_at       TIMESTAMP,
    UNIQUE (tenant_id, email)
);
CREATE INDEX IF NOT EXISTS idx_operator_tenant ON operator(tenant_id, status);

-- 操作者 ↔ 角色(多对多)
CREATE TABLE IF NOT EXISTS operator_role (
    operator_id       VARCHAR(64) NOT NULL REFERENCES operator(id),
    role_id           INT NOT NULL REFERENCES role(id),
    granted_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    granted_by        VARCHAR(64) REFERENCES operator(id),
    PRIMARY KEY (operator_id, role_id)
);

-- Maker-Checker 审批流(固件刷写 / 高危写入)
CREATE TABLE IF NOT EXISTS approval_record (
    id                BIGSERIAL PRIMARY KEY,
    approval_id       VARCHAR(64) NOT NULL UNIQUE,
    tenant_id         VARCHAR(64) NOT NULL,
    action            VARCHAR(64) NOT NULL,
    resource_ref      VARCHAR(128) NOT NULL,
    requested_by      VARCHAR(64) NOT NULL REFERENCES operator(id),
    requested_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    approved_by       VARCHAR(64) REFERENCES operator(id),
    approved_at       TIMESTAMP,
    status            VARCHAR(16) DEFAULT 'pending',       -- pending/approved/rejected/expired
    expires_at        TIMESTAMP,
    approval_note     TEXT,
    CHECK (requested_by <> approved_by OR approved_by IS NULL)
);
CREATE INDEX IF NOT EXISTS idx_approval_status ON approval_record(status, expires_at);

-- ==========================================
-- 3. ODX 核心配置表
-- ==========================================

-- 车型基础信息
CREATE TABLE IF NOT EXISTS odx_vehicle_model (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         VARCHAR(64) NOT NULL,
    model_code        VARCHAR(32) NOT NULL,
    model_name        VARCHAR(64) NOT NULL,
    odx_version       VARCHAR(32),
    import_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    source_file       VARCHAR(255),
    UNIQUE (tenant_id, model_code)
);

-- ECU 定义
CREATE TABLE IF NOT EXISTS odx_ecu (
    id                BIGSERIAL PRIMARY KEY,
    model_id          BIGINT NOT NULL REFERENCES odx_vehicle_model(id),
    ecu_code          VARCHAR(32) NOT NULL,
    ecu_name          VARCHAR(64) NOT NULL,
    tx_id             VARCHAR(16) NOT NULL,
    rx_id             VARCHAR(16) NOT NULL,
    protocol          VARCHAR(32) DEFAULT 'UDS_ON_CAN',
    sec_algo_ref      VARCHAR(64)
);

-- 诊断服务定义
CREATE TABLE IF NOT EXISTS odx_diag_service (
    id                BIGSERIAL PRIMARY KEY,
    ecu_id            BIGINT NOT NULL REFERENCES odx_ecu(id),
    service_code      VARCHAR(16) NOT NULL,
    sub_function      VARCHAR(16),
    service_name      VARCHAR(64) NOT NULL,
    display_name      VARCHAR(128) NOT NULL,
    description       TEXT,
    category          VARCHAR(32) NOT NULL,
    request_raw_hex   VARCHAR(255) NOT NULL,
    response_id_hex   VARCHAR(255) NOT NULL,
    requires_security BOOLEAN DEFAULT FALSE,
    required_sec_level VARCHAR(16),
    required_session  VARCHAR(16),
    macro_type        VARCHAR(32) NOT NULL,
    is_enabled        BOOLEAN DEFAULT TRUE,
    safety_critical   BOOLEAN DEFAULT FALSE                -- 关联 RBAC 权限矩阵(§15.4.2)
);

-- 参数编解码规则
CREATE TABLE IF NOT EXISTS odx_param_codec (
    id                BIGSERIAL PRIMARY KEY,
    service_id        BIGINT NOT NULL REFERENCES odx_diag_service(id),
    param_name        VARCHAR(64) NOT NULL,
    display_name      VARCHAR(64) NOT NULL,
    byte_offset       INT NOT NULL,
    bit_offset        INT DEFAULT 0,
    bit_length        INT NOT NULL,
    data_type         VARCHAR(32) DEFAULT 'unsigned',
    formula           VARCHAR(255),
    unit              VARCHAR(16),
    enum_mapping      JSONB,
    min_value         DECIMAL(10, 2),
    max_value         DECIMAL(10, 2)
);

-- 条件任务允许订阅的信号白名单(见协议 §8.3.2)
-- v1.2: version 列支持 DBC 白名单版本协商,车端版本落后时返回 queue_reject SIGNAL_CATALOG_STALE
CREATE TABLE IF NOT EXISTS condition_signal_catalog (
    id                BIGSERIAL PRIMARY KEY,
    model_id          BIGINT NOT NULL REFERENCES odx_vehicle_model(id),
    signal_name       VARCHAR(64) NOT NULL,
    display_name      VARCHAR(128) NOT NULL,
    data_type         VARCHAR(32),                          -- unsigned/signed/enum/float
    enum_mapping      JSONB,
    source            VARCHAR(32) NOT NULL,                 -- dbc_broadcast/dtc_poll/internal_timer/gps
    description       TEXT,
    enabled           BOOLEAN DEFAULT TRUE,
    version           INT NOT NULL DEFAULT 1,               -- 白名单版本号,OTA 更新时递增
    UNIQUE (model_id, signal_name, version)
);
CREATE INDEX IF NOT EXISTS idx_signal_catalog_version
    ON condition_signal_catalog(model_id, version);

-- ==========================================
-- 4. 诊断流水与任务模型
-- ==========================================

-- 诊断记录(审计主锚点)
CREATE TABLE IF NOT EXISTS diag_record (
    id                BIGSERIAL PRIMARY KEY,
    msg_id            VARCHAR(64) NOT NULL UNIQUE,
    tenant_id         VARCHAR(64) NOT NULL,
    vin               VARCHAR(17) NOT NULL,
    ecu_name          VARCHAR(64),
    act               VARCHAR(32) NOT NULL,
    req_raw_hex       TEXT,
    res_raw_hex       TEXT,
    translated        JSONB,
    status            INT DEFAULT -1,
    error_code        VARCHAR(32),
    operator_id       VARCHAR(64),                          -- FK 软关联 operator.id
    operator_role     VARCHAR(32),
    ticket_id         VARCHAR(64),
    task_id           VARCHAR(64),                          -- 软 FK -> task_definition
    execution_seq     INT,
    script_id         VARCHAR(64),
    trace_id          VARCHAR(32),
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    responded_at      TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_diag_record_vin_time ON diag_record(vin, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_diag_record_task ON diag_record(task_id, execution_seq) WHERE task_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_diag_record_tenant_op ON diag_record(tenant_id, operator_id, created_at DESC);

-- 批量任务(单 ECU 多步序列)
-- ⚠️ DEPRECATED (v1.2 R8):v1.0 遗留表,新代码不应使用。
-- 所有批量/周期/脚本任务统一走 task_definition + task_dispatch_record,batch_task 仅供历史数据查询。
-- MVP 完成后的下个里程碑将迁移历史数据到 task_execution_log 并物理删除本表。
CREATE TABLE IF NOT EXISTS batch_task (
    id                BIGSERIAL PRIMARY KEY,
    task_id           VARCHAR(64) NOT NULL UNIQUE,
    tenant_id         VARCHAR(64) NOT NULL,
    vin               VARCHAR(17) NOT NULL,
    ecu_name          VARCHAR(64),
    overall_status    INT DEFAULT -1,
    total_steps       INT NOT NULL,
    strategy          INT DEFAULT 1,
    request_payload   JSONB NOT NULL,
    result_payload    JSONB,
    operator_id       VARCHAR(64),
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at      TIMESTAMP
);

-- 任务定义
CREATE TABLE IF NOT EXISTS task_definition (
    id                BIGSERIAL PRIMARY KEY,
    task_id           VARCHAR(64) NOT NULL UNIQUE,
    tenant_id         VARCHAR(64) NOT NULL,
    task_name         VARCHAR(256) NOT NULL,
    version           INT NOT NULL DEFAULT 1,
    supersedes_task_id VARCHAR(64),
    priority          INT DEFAULT 5 CHECK (priority BETWEEN 0 AND 9),
    valid_from        TIMESTAMP,
    valid_until       TIMESTAMP,
    execute_valid_from  TIMESTAMP,
    execute_valid_until TIMESTAMP,
    schedule_type     VARCHAR(32) NOT NULL,                 -- once/periodic/timed/conditional
    schedule_config   JSONB NOT NULL,
    miss_policy       VARCHAR(32) DEFAULT 'fire_once',      -- skip_all/fire_once/fire_all
    payload_type      VARCHAR(32) DEFAULT 'batch',          -- batch/script
    diag_payload      JSONB NOT NULL,
    payload_hash      CHAR(64) NOT NULL,                    -- SHA-256 of diag_payload
    requires_approval BOOLEAN DEFAULT FALSE,
    approval_id       VARCHAR(64),
    status            VARCHAR(32) DEFAULT 'draft',          -- draft/pending_approval/active/paused/completed/expired
    created_by        VARCHAR(64) REFERENCES operator(id),
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CHECK (execute_valid_until IS NULL OR valid_until IS NULL OR execute_valid_until >= valid_until),
    -- v1.2 R3: 防永动机任务。周期/条件任务若 maxExecutions=-1 (无限) 则 executeValidUntil 必须有界
    -- 单次/定时任务不检(executeValidUntil 天然由 executeAt(List) 隐含上界)
    CHECK (
        schedule_type NOT IN ('periodic', 'conditional')
        OR (schedule_config->>'maxExecutions')::int <> -1
        OR execute_valid_until IS NOT NULL
    )
);
CREATE INDEX IF NOT EXISTS idx_task_def_status ON task_definition(status);
CREATE INDEX IF NOT EXISTS idx_task_def_priority ON task_definition(priority, created_at);
CREATE INDEX IF NOT EXISTS idx_task_def_tenant ON task_definition(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_task_def_supersedes ON task_definition(supersedes_task_id) WHERE supersedes_task_id IS NOT NULL;

-- 任务目标
CREATE TABLE IF NOT EXISTS task_target (
    id                BIGSERIAL PRIMARY KEY,
    task_id           VARCHAR(64) NOT NULL REFERENCES task_definition(task_id),
    target_type       VARCHAR(32) NOT NULL,                 -- vin_list/model/tag/all
    target_value      JSONB NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_task_target_task ON task_target(task_id);

-- 任务分发记录(每辆车一行)
-- v1.2: ecu_scope 新增(ECU 级互斥,协议 §10.2);dispatch_status 扩充 superseding
CREATE TABLE IF NOT EXISTS task_dispatch_record (
    id                BIGSERIAL PRIMARY KEY,
    task_id           VARCHAR(64) NOT NULL REFERENCES task_definition(task_id),
    tenant_id         VARCHAR(64) NOT NULL,
    vin               VARCHAR(17) NOT NULL,
    ecu_scope         JSONB,                                -- ECU 名数组,如 '["VCU","BMS"]'。与协议 ecuScope 对齐
    dispatch_status   VARCHAR(32) DEFAULT 'pending_online'
        CHECK (dispatch_status IN (
            'pending_online','dispatched','queued','scheduling','executing',
            'paused','deferred','superseding',
            'completed','failed','canceled','expired')),
    dispatched_at     TIMESTAMP,
    completed_at      TIMESTAMP,
    result_payload    JSONB,
    retry_count       INT DEFAULT 0,
    last_error        VARCHAR(128),
    superseded_by     VARCHAR(64),
    current_execution_count INT DEFAULT 0,                   -- v1.2: 云端以此 max(车端 begin/end 上报) 为权威值
    last_reported_at  TIMESTAMP,
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (task_id, vin)
);
CREATE INDEX IF NOT EXISTS idx_dispatch_task_vin ON task_dispatch_record(task_id, vin);
CREATE INDEX IF NOT EXISTS idx_dispatch_status ON task_dispatch_record(dispatch_status);
CREATE INDEX IF NOT EXISTS idx_dispatch_vin_pending ON task_dispatch_record(vin, dispatch_status)
    WHERE dispatch_status = 'pending_online';
CREATE INDEX IF NOT EXISTS idx_dispatch_ecu_scope ON task_dispatch_record USING GIN (ecu_scope);

-- 任务分发候补表(queue_reject 反压)
CREATE TABLE IF NOT EXISTS task_dispatch_pending_backlog (
    id                BIGSERIAL PRIMARY KEY,
    task_id           VARCHAR(64) NOT NULL REFERENCES task_definition(task_id),
    vin               VARCHAR(17) NOT NULL,
    priority          INT NOT NULL,
    reject_reason     VARCHAR(32) NOT NULL,
    rejected_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    retry_after_at    TIMESTAMP,
    attempt_count     INT DEFAULT 1,
    UNIQUE (task_id, vin)
);
CREATE INDEX IF NOT EXISTS idx_backlog_replay ON task_dispatch_pending_backlog(vin, retry_after_at, priority);

-- 任务执行日志(周期任务每次独立一行)
-- v1.2: begin/end 双 ack 支持周期计数一致性(协议 §8.5);execution_seq 由车端权威分配,ON CONFLICT DO NOTHING 幂等去重
CREATE TABLE IF NOT EXISTS task_execution_log (
    id                BIGSERIAL PRIMARY KEY,
    task_id           VARCHAR(64) NOT NULL REFERENCES task_definition(task_id),
    tenant_id         VARCHAR(64) NOT NULL,
    vin               VARCHAR(17) NOT NULL,
    execution_seq     INT NOT NULL,
    trigger_time      TIMESTAMP NOT NULL,
    overall_status    INT,
    result_payload    JSONB,
    execution_duration INT,
    miss_compensation JSONB,
    begin_reported_at TIMESTAMP,                             -- v1.2: execution_begin 到达时间
    end_reported_at   TIMESTAMP,                             -- v1.2: execution_end 到达时间
    begin_msg_id      VARCHAR(64),                           -- v1.2: execution_begin 报文 msgId
    end_msg_id        VARCHAR(64),                           -- v1.2: execution_end 报文 msgId
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (task_id, vin, execution_seq)                     -- 幂等锚点,重复 begin/end 到达时 ON CONFLICT DO NOTHING
);
CREATE INDEX IF NOT EXISTS idx_exec_log_task_vin ON task_execution_log(task_id, vin, execution_seq);
CREATE INDEX IF NOT EXISTS idx_exec_log_tenant_time ON task_execution_log(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_exec_log_pending_end ON task_execution_log(task_id, vin)
    WHERE end_reported_at IS NULL;                           -- 找出 begin 后超时未 end 的执行

-- v1.2: 聚合报文分片暂存表(协议 §8.5 schedule_resp 拆包)
-- 车端离线累积结果 > 200KB 时拆包上报,云端以 aggregation_id 重组
CREATE TABLE IF NOT EXISTS task_result_chunk (
    id                BIGSERIAL PRIMARY KEY,
    aggregation_id    VARCHAR(64) NOT NULL,                  -- 同一次聚合上报的全局 ID (UUID)
    tenant_id         VARCHAR(64) NOT NULL,
    vin               VARCHAR(17) NOT NULL,
    task_id           VARCHAR(64) NOT NULL REFERENCES task_definition(task_id),
    chunk_seq         INT NOT NULL,                          -- 1-based
    chunk_total       INT NOT NULL,                          -- 期望的总分片数(车端在首片声明)
    payload           JSONB NOT NULL,                        -- 该分片的 resultsHistory 子集
    truncated         BOOLEAN DEFAULT FALSE,                 -- 最后一片若被 maxChunks 截断置 true
    dropped_count     INT DEFAULT 0,                         -- 被丢弃的最老条数
    received_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (aggregation_id, chunk_seq)
);
CREATE INDEX IF NOT EXISTS idx_chunk_agg_id ON task_result_chunk(aggregation_id, chunk_seq);
CREATE INDEX IF NOT EXISTS idx_chunk_task_vin ON task_result_chunk(task_id, vin, received_at DESC);

-- v1.2: 车端时钟信任状态(协议 §17)
-- 车端每 24h 或启动时发 time_sync_request, 云端响应后更新此表
CREATE TABLE IF NOT EXISTS vehicle_clock_trust (
    vin               VARCHAR(17) PRIMARY KEY,
    tenant_id         VARCHAR(64) NOT NULL,
    last_sync_at      TIMESTAMP,                             -- 云端收到 request 的时刻
    drift_ms          BIGINT,                                -- 车端声称时间 - 云端权威时间,正数=车端超前
    trust_status      VARCHAR(16) DEFAULT 'unknown'          -- trusted / drifting / untrusted / unknown
        CHECK (trust_status IN ('trusted','drifting','untrusted','unknown')),
    max_drift_ms      BIGINT DEFAULT 60000,                  -- 配置阈值,默认 ±60s
    last_sync_msg_id  VARCHAR(64),
    updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_clock_trust_status ON vehicle_clock_trust(trust_status, last_sync_at);

-- ==========================================
-- 5. 状态、事件、审计与 SSE
-- ==========================================

-- 车辆在线状态
CREATE TABLE IF NOT EXISTS vehicle_online_status (
    id                BIGSERIAL PRIMARY KEY,
    vin               VARCHAR(17) NOT NULL UNIQUE,
    tenant_id         VARCHAR(64) NOT NULL,
    is_online         BOOLEAN DEFAULT FALSE,
    last_online_at    TIMESTAMP,
    last_offline_at   TIMESTAMP,
    mqtt_client_id    VARCHAR(128),
    cert_cn           VARCHAR(64),
    cert_expires_at   TIMESTAMP,
    agent_version     VARCHAR(32),
    updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_vehicle_online ON vehicle_online_status(is_online);
CREATE INDEX IF NOT EXISTS idx_vehicle_cert_expiry ON vehicle_online_status(cert_expires_at)
    WHERE cert_expires_at IS NOT NULL;

-- 通道事件日志(SSE 多源回填的数据源之一)
CREATE TABLE IF NOT EXISTS channel_event_log (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         VARCHAR(64) NOT NULL,
    vin               VARCHAR(17) NOT NULL,
    channel_id        VARCHAR(64),
    event             VARCHAR(32) NOT NULL,
    current_session   VARCHAR(8),
    current_sec_level VARCHAR(8),
    reason            VARCHAR(64),
    related_task_id   VARCHAR(64),
    operator_id       VARCHAR(64),
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_channel_event_vin ON channel_event_log(vin, created_at DESC);

-- 条件触发命中日志
CREATE TABLE IF NOT EXISTS condition_fired_log (
    id                BIGSERIAL PRIMARY KEY,
    task_id           VARCHAR(64) NOT NULL REFERENCES task_definition(task_id),
    tenant_id         VARCHAR(64) NOT NULL,
    vin               VARCHAR(17) NOT NULL,
    trigger_type      VARCHAR(32) NOT NULL,
    trigger_snapshot  JSONB NOT NULL,
    action_taken      VARCHAR(16) NOT NULL,                 -- queued/deferred/skipped
    execution_seq     INT,
    fired_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_cond_fired_task ON condition_fired_log(task_id, fired_at DESC);
CREATE INDEX IF NOT EXISTS idx_cond_fired_vin ON condition_fired_log(vin, fired_at DESC);

-- 固件传输会话(断点续传)
CREATE TABLE IF NOT EXISTS flash_session (
    id                BIGSERIAL PRIMARY KEY,
    transfer_session_id VARCHAR(64) NOT NULL UNIQUE,
    tenant_id         VARCHAR(64) NOT NULL,
    vin               VARCHAR(17) NOT NULL,
    task_id           VARCHAR(64) REFERENCES task_definition(task_id),
    file_sha256       CHAR(64) NOT NULL,
    file_size         BIGINT NOT NULL,
    last_confirmed_offset BIGINT DEFAULT 0,
    status            VARCHAR(32) DEFAULT 'in_progress',    -- in_progress/completed/rolled_back/failed
    target_partition  VARCHAR(8),                           -- A/B
    rollback_on_failure BOOLEAN DEFAULT TRUE,
    approval_id       VARCHAR(64),
    started_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at      TIMESTAMP,
    last_heartbeat_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_flash_session_status ON flash_session(status, last_heartbeat_at);

-- SSE 事件流水(断线补发的统一数据源)
CREATE TABLE IF NOT EXISTS sse_event (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         VARCHAR(64) NOT NULL,
    vin               VARCHAR(17) NOT NULL,
    event_type        VARCHAR(32) NOT NULL,                 -- diag-result/channel-event/task-progress/condition-fired
    source_type       VARCHAR(32) NOT NULL,                 -- diag_record/task_execution_log/channel_event_log/...
    source_id         BIGINT NOT NULL,
    payload_summary   JSONB NOT NULL,
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sse_event_vin_id ON sse_event(vin, id);

-- 安全审计日志(独立账号 append-only)
CREATE TABLE IF NOT EXISTS security_audit_log (
    id                BIGSERIAL PRIMARY KEY,
    audit_id          VARCHAR(64) NOT NULL UNIQUE,
    msg_id            VARCHAR(64),
    tenant_id         VARCHAR(64) NOT NULL,
    operator_id       VARCHAR(64),
    operator_role     VARCHAR(32),
    ticket_id         VARCHAR(64),
    vin               VARCHAR(17),
    action            VARCHAR(64) NOT NULL,
    resource_type     VARCHAR(32),
    req_payload       JSONB,
    res_payload       JSONB,
    result            VARCHAR(16) NOT NULL,                 -- success/failed/timeout/rejected
    client_ip         VARCHAR(45),
    user_agent        VARCHAR(512),
    chain_id          VARCHAR(64),
    timestamp         TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_audit_vin_time ON security_audit_log(vin, timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_audit_operator_time ON security_audit_log(operator_id, timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_audit_tenant_action ON security_audit_log(tenant_id, action, timestamp DESC);

-- ==========================================
-- 6. 行级安全策略(RLS)多租户隔离
-- ==========================================
-- Spring Boot 通过 @Transactional 拦截器设置 SET LOCAL app.tenant_id = <jwt.tenantId>
-- 所有查询自动被过滤,即便 SQL 注入也无法跨租户读取

ALTER TABLE task_definition ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_dispatch_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_execution_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE diag_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicle_online_status ENABLE ROW LEVEL SECURITY;
ALTER TABLE channel_event_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE condition_fired_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE flash_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE sse_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE security_audit_log ENABLE ROW LEVEL SECURITY;

-- PG 不支持 CREATE POLICY IF NOT EXISTS,改用 DROP + CREATE 幂等
DROP POLICY IF EXISTS rls_tenant_task_def       ON task_definition;
DROP POLICY IF EXISTS rls_tenant_dispatch       ON task_dispatch_record;
DROP POLICY IF EXISTS rls_tenant_exec_log       ON task_execution_log;
DROP POLICY IF EXISTS rls_tenant_diag_record    ON diag_record;
DROP POLICY IF EXISTS rls_tenant_vehicle        ON vehicle_online_status;
DROP POLICY IF EXISTS rls_tenant_channel_event  ON channel_event_log;
DROP POLICY IF EXISTS rls_tenant_cond_fired     ON condition_fired_log;
DROP POLICY IF EXISTS rls_tenant_flash          ON flash_session;
DROP POLICY IF EXISTS rls_tenant_sse            ON sse_event;
DROP POLICY IF EXISTS rls_tenant_audit          ON security_audit_log;

CREATE POLICY rls_tenant_task_def ON task_definition
    USING (tenant_id = current_setting('app.tenant_id', true));
CREATE POLICY rls_tenant_dispatch ON task_dispatch_record
    USING (tenant_id = current_setting('app.tenant_id', true));
CREATE POLICY rls_tenant_exec_log ON task_execution_log
    USING (tenant_id = current_setting('app.tenant_id', true));
CREATE POLICY rls_tenant_diag_record ON diag_record
    USING (tenant_id = current_setting('app.tenant_id', true));
CREATE POLICY rls_tenant_vehicle ON vehicle_online_status
    USING (tenant_id = current_setting('app.tenant_id', true));
CREATE POLICY rls_tenant_channel_event ON channel_event_log
    USING (tenant_id = current_setting('app.tenant_id', true));
CREATE POLICY rls_tenant_cond_fired ON condition_fired_log
    USING (tenant_id = current_setting('app.tenant_id', true));
CREATE POLICY rls_tenant_flash ON flash_session
    USING (tenant_id = current_setting('app.tenant_id', true));
CREATE POLICY rls_tenant_sse ON sse_event
    USING (tenant_id = current_setting('app.tenant_id', true));
CREATE POLICY rls_tenant_audit ON security_audit_log
    USING (tenant_id = current_setting('app.tenant_id', true));

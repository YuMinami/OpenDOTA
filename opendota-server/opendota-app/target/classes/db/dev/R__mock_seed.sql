-- Flyway Repeatable 迁移:Mock 数据播种
-- 运行时机: V1 之后,每次哈希变更重新执行
-- 生产环境必须关闭: spring.flyway.placeholders.seed_mock=false (见 application-prod.yml)
-- dev/staging 自动执行,方便 MVP 验证和集成测试

-- ==========================================
-- Mock 数据(MVP 验证用)
-- ==========================================

TRUNCATE TABLE
    operator_role, approval_record, condition_signal_catalog,
    diag_record, batch_task,
    task_dispatch_pending_backlog, task_execution_log,
    task_dispatch_record, task_target, task_definition,
    channel_event_log, condition_fired_log, flash_session, sse_event, security_audit_log,
    vehicle_online_status,
    odx_param_codec, odx_diag_service, odx_ecu, odx_vehicle_model,
    operator, role
RESTART IDENTITY CASCADE;

-- 角色字典
INSERT INTO role (role_name, description) VALUES
    ('viewer', '只读查看,售后客服'),
    ('engineer', '一线诊断工程师,可执行常规诊断'),
    ('senior_engineer', '资深工程师,可 force 抢占、取消他人任务'),
    ('admin', '平台管理员,可刷写 ECU 固件、导入 ODX、导出审计'),
    ('system', '系统账号,任务触发的下发使用');

-- Mock 操作者(奇瑞租户)
INSERT INTO operator (id, tenant_id, display_name, email, status) VALUES
    ('eng-12345',   'chery-hq', '张三(一线工程师)',    'zhangsan@chery.example.com',  'active'),
    ('seng-67890',  'chery-hq', '李四(资深工程师)',    'lisi@chery.example.com',      'active'),
    ('admin-0001',  'chery-hq', '王五(平台管理员)',    'wangwu@chery.example.com',    'active'),
    ('system',      'chery-hq', '系统账号',            NULL,                           'active');

-- 授予角色
INSERT INTO operator_role (operator_id, role_id, granted_by) VALUES
    ('eng-12345',  (SELECT id FROM role WHERE role_name = 'engineer'),        'admin-0001'),
    ('seng-67890', (SELECT id FROM role WHERE role_name = 'senior_engineer'), 'admin-0001'),
    ('admin-0001', (SELECT id FROM role WHERE role_name = 'admin'),           'admin-0001'),
    ('system',     (SELECT id FROM role WHERE role_name = 'system'),          'admin-0001');

-- 车型
INSERT INTO odx_vehicle_model (id, tenant_id, model_code, model_name, odx_version)
VALUES (1, 'chery-hq', 'CHERY_EXEED', '奇瑞 星途瑶光', 'v1.0_mock');

-- ECU
INSERT INTO odx_ecu (id, model_id, ecu_code, ecu_name, tx_id, rx_id, protocol, sec_algo_ref) VALUES
    (1, 1, 'VCU_BAS_V1', 'VCU 整车控制器',     '0x7E0',  '0x7E8',  'UDS_ON_CAN',  'Algo_Chery_VCU'),
    (2, 1, 'GW_ETH_V1',  'GW 中央网关(以太网)', '0x0E80', '0x1010', 'UDS_ON_DOIP', NULL),
    (3, 1, 'BMS_V1',     'BMS 电池管理系统',    '0x7E3',  '0x7EB',  'UDS_ON_CAN',  'Algo_Chery_BMS');

-- 诊断服务 Mock
INSERT INTO odx_diag_service (id, ecu_id, service_code, sub_function, service_name, display_name, category, request_raw_hex, response_id_hex, macro_type, required_session) VALUES
    (101, 1, '0x22', 'F190', 'Read_VIN',             '读取车辆识别码(VIN)',      '识别信息', '22F190',   '62F190',  'raw_uds',       '01'),
    (102, 1, '0x22', 'F112', 'Read_BattTemp',        '读取电池温度',              '运行数据', '22F112',   '62F112',  'raw_uds',       '01'),
    (103, 1, '0x10', '03',   'DiagSession_Extended', '进入扩展会话',              '会话控制', '1003',     '5003',    'raw_uds',       NULL),
    (104, 1, '0x27', '01',   'SecAccess_Lv1',        '请求安全解锁(Lv1)',         '安全访问', '2701',     '6701',    'macro_security','03'),
    (201, 2, '0x22', 'F195', 'Read_GW_SwVer',        '读取网关软件版本号',        '识别信息', '22F195',   '62F195',  'raw_uds',       '01'),
    (202, 2, '0x22', 'F191', 'Read_GW_HwVer',        '读取网关硬件版本号',        '识别信息', '22F191',   '62F191',  'raw_uds',       '01'),
    (301, 3, '0x19', '0209', 'Read_BMS_DTC',         '读取 BMS 故障码',           '故障诊断', '190209',   '590209',  'raw_uds',       '01');

-- 高危服务标记(例如写入 BMS 充电电流上限 -> safety_critical=true)
-- Mock 中不插入具体的 2E,仅演示字段
-- INSERT ... safety_critical = TRUE ...

-- 参数解码规则(电池温度)
INSERT INTO odx_param_codec (service_id, param_name, display_name, byte_offset, bit_length, data_type, formula, unit)
VALUES (102, 'BattTemp', '电池温度探针 A', 3, 8, 'unsigned', 'raw * 1 - 40', '°C');

-- 条件任务信号白名单
INSERT INTO condition_signal_catalog (model_id, signal_name, display_name, data_type, enum_mapping, source, description) VALUES
    (1, 'KL15_STATUS', '点火钥匙状态', 'enum', '{"0":"OFF","1":"ACC","2":"ON","3":"START"}',
     'dbc_broadcast', '用于上电自检触发'),
    (1, 'VEHICLE_SPEED', '车速',        'unsigned', NULL, 'dbc_broadcast', 'km/h'),
    (1, 'BMS_DTC_ACTIVE', 'BMS 活跃故障码数量', 'unsigned', NULL, 'dtc_poll', '每 60s 轮询');

-- Mock 车辆在线状态(2 辆测试车)
INSERT INTO vehicle_online_status (vin, tenant_id, is_online, last_online_at, mqtt_client_id, cert_cn, cert_expires_at, agent_version) VALUES
    ('LSVWA234567890123', 'chery-hq', TRUE,  CURRENT_TIMESTAMP,                            'cli-vw-001', 'LSVWA234567890123', CURRENT_TIMESTAMP + INTERVAL '3 years', 'agent-1.2.0'),
    ('LSVWB987654321098', 'chery-hq', FALSE, CURRENT_TIMESTAMP - INTERVAL '2 hours',       'cli-vw-002', 'LSVWB987654321098', CURRENT_TIMESTAMP + INTERVAL '3 years', 'agent-1.1.5');

-- Mock 任务(一个周期任务:每 5 分钟读 BMS DTC)
INSERT INTO task_definition (
    task_id, tenant_id, task_name, version, priority,
    valid_from, valid_until, execute_valid_from, execute_valid_until,
    schedule_type, schedule_config, miss_policy,
    payload_type, diag_payload, payload_hash,
    status, created_by
) VALUES (
    'tsk_demo_bms_dtc_scan', 'chery-hq', '全车型 BMS 周期 DTC 扫描', 1, 5,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '7 days',
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '14 days',
    'periodic',
    '{"mode":"periodic","cronExpression":"0 */5 * * * ?","maxExecutions":-1,"validWindow":{"startTime":0,"endTime":9999999999999}}'::jsonb,
    'fire_once',
    'batch',
    '{"taskId":"tsk_demo_bms_dtc_scan","ecuName":"BMS","txId":"0x7E3","rxId":"0x7EB","strategy":1,"steps":[{"seqId":1,"type":"raw_uds","data":"190209","timeoutMs":3000}]}'::jsonb,
    encode(digest('mock-demo-payload-hash', 'sha256'), 'hex'),
    'active', 'seng-67890'
);

INSERT INTO task_target (task_id, target_type, target_value) VALUES
    ('tsk_demo_bms_dtc_scan', 'model', '{"modelCode":"CHERY_EXEED"}'::jsonb);

INSERT INTO task_dispatch_record (task_id, tenant_id, vin, ecu_scope, dispatch_status, dispatched_at) VALUES
    ('tsk_demo_bms_dtc_scan', 'chery-hq', 'LSVWA234567890123', '["BMS"]'::jsonb, 'scheduling', CURRENT_TIMESTAMP),
    ('tsk_demo_bms_dtc_scan', 'chery-hq', 'LSVWB987654321098', '["BMS"]'::jsonb, 'pending_online', NULL);

-- Mock 审计日志(最近一次操作)
INSERT INTO security_audit_log (audit_id, msg_id, tenant_id, operator_id, operator_role, ticket_id, vin, action, resource_type, result) VALUES
    (uuid_generate_v4()::text, uuid_generate_v4()::text, 'chery-hq', 'seng-67890', 'senior_engineer',
     'DIAG-2026-0417-001', 'LSVWA234567890123', 'channel_open', 'channel', 'success');

-- 配置完毕,可开始进行 SELECT / 业务验证

-- ==========================================
-- 8. (生产环境可选) 安全审计 WORM 与独立 tablespace
-- ==========================================
-- 以下 DDL 在 MVP / 开发环境可保持注释;生产上线前由 DBA 根据合规要求启用。
-- 目的:满足《汽车数据安全管理若干规定》对审计日志 3 年不可篡改的硬要求。

-- [1] 独立 tablespace(将审计日志物理隔离到专用磁盘/加密卷)
-- CREATE TABLESPACE ts_audit LOCATION '/pgdata/audit';
-- ALTER TABLE security_audit_log SET TABLESPACE ts_audit;

-- [2] Append-only trigger: 禁止 UPDATE / DELETE, 仅允许 INSERT
-- CREATE OR REPLACE FUNCTION trg_audit_append_only() RETURNS trigger AS $$
-- BEGIN
--     RAISE EXCEPTION 'security_audit_log is append-only (WORM). op=% audit_id=%',
--         TG_OP, COALESCE(OLD.audit_id, NEW.audit_id);
-- END;
-- $$ LANGUAGE plpgsql;
--
-- DROP TRIGGER IF EXISTS trg_audit_block_update ON security_audit_log;
-- CREATE TRIGGER trg_audit_block_update
--     BEFORE UPDATE OR DELETE OR TRUNCATE ON security_audit_log
--     FOR EACH STATEMENT EXECUTE FUNCTION trg_audit_append_only();

-- [3] 独立写入角色(业务读写账号 REVOKE UPDATE/DELETE)
-- REVOKE UPDATE, DELETE, TRUNCATE ON security_audit_log FROM app_rw;
-- GRANT  INSERT, SELECT                ON security_audit_log TO   app_rw;

-- [4] 冷归档到 S3 Object Lock (Compliance 模式, 3 年)
--     由外部 ETL/ELT 任务驱动, 不在本脚本内:
--     - 每日 00:30 将 > 90 天的行搬到 s3://dota-audit-archive/{yyyy}/{mm}/
--     - S3 Bucket 启用 Object Lock Compliance mode, Retention = 3y
--     - 跨区复制到异地灾备桶, RPO ≤ 5min / RTO ≤ 1h

-- OpenDOTA PostgreSQL 数据库初始化与 Mock 数据脚本
-- 版本: v1.0
-- 描述: 包含核心 ODX 配置表、诊断记录表，并预先注入了用于 MVP 验证的假数据

-- ==========================================
-- 1. 结构定义 (DDL)
-- ==========================================

-- 启用 uuid 扩展
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 表 1：车型基础信息
CREATE TABLE IF NOT EXISTS odx_vehicle_model (
    id BIGSERIAL PRIMARY KEY,
    model_code VARCHAR(32) NOT NULL UNIQUE,
    model_name VARCHAR(64) NOT NULL,
    odx_version VARCHAR(32),
    import_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    source_file VARCHAR(255)
);

-- 表 2：ECU 定义
CREATE TABLE IF NOT EXISTS odx_ecu (
    id BIGSERIAL PRIMARY KEY,
    model_id BIGINT NOT NULL REFERENCES odx_vehicle_model(id),
    ecu_code VARCHAR(32) NOT NULL,
    ecu_name VARCHAR(64) NOT NULL,
    tx_id VARCHAR(16) NOT NULL,
    rx_id VARCHAR(16) NOT NULL,
    protocol VARCHAR(32) DEFAULT 'UDS_ON_CAN',
    sec_algo_ref VARCHAR(64)
);

-- 表 3：诊断服务定义 (最核心表)
CREATE TABLE IF NOT EXISTS odx_diag_service (
    id BIGSERIAL PRIMARY KEY,
    ecu_id BIGINT NOT NULL REFERENCES odx_ecu(id),
    service_code VARCHAR(16) NOT NULL,
    sub_function VARCHAR(16),
    service_name VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    description TEXT,
    category VARCHAR(32) NOT NULL,
    request_raw_hex VARCHAR(255) NOT NULL,
    response_id_hex VARCHAR(255) NOT NULL,
    requires_security BOOLEAN DEFAULT FALSE,
    required_sec_level VARCHAR(16),
    required_session VARCHAR(16),
    macro_type VARCHAR(32) NOT NULL,  -- raw_uds, macro_security, etc.
    is_enabled BOOLEAN DEFAULT TRUE
);

-- 表 4：参数编解码规则
CREATE TABLE IF NOT EXISTS odx_param_codec (
    id BIGSERIAL PRIMARY KEY,
    service_id BIGINT NOT NULL REFERENCES odx_diag_service(id),
    param_name VARCHAR(64) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    byte_offset INT NOT NULL,
    bit_offset INT DEFAULT 0,
    bit_length INT NOT NULL,
    data_type VARCHAR(32) DEFAULT 'unsigned',
    formula VARCHAR(255),
    unit VARCHAR(16),
    enum_mapping JSONB,
    min_value DECIMAL(10, 2),
    max_value DECIMAL(10, 2)
);

-- 表 5：诊断记录 (日常流水志)
CREATE TABLE IF NOT EXISTS diag_record (
    id BIGSERIAL PRIMARY KEY,
    msg_id VARCHAR(64) NOT NULL UNIQUE,
    vin VARCHAR(17) NOT NULL,
    ecu_name VARCHAR(64),
    act VARCHAR(32) NOT NULL,
    req_raw_hex TEXT,
    res_raw_hex TEXT,
    translated JSONB,
    status INT DEFAULT -1,
    error_code VARCHAR(32),
    operator_id VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    responded_at TIMESTAMP
);

CREATE INDEX idx_diag_record_vin_time ON diag_record(vin, created_at DESC);

-- ==========================================
-- 2. MVP 验证用的 Mock 假数据注入
-- ==========================================

-- 清空旧数据 (级联)
TRUNCATE TABLE odx_vehicle_model RESTART IDENTITY CASCADE;

-- 插入车型（例如：奇瑞 星途）
INSERT INTO odx_vehicle_model (id, model_code, model_name, odx_version)
VALUES (1, 'CHERY_EXEED', '奇瑞 星途瑶光', 'v1.0_mock');

-- 插入 ECU (假设我们调试的是 VCU，走 CAN 总线)
INSERT INTO odx_ecu (id, model_id, ecu_code, ecu_name, tx_id, rx_id, protocol, sec_algo_ref)
VALUES (1, 1, 'VCU_BAS_V1', 'VCU 整车控制器', '0x7E0', '0x7E8', 'UDS_ON_CAN', 'Algo_Chery_VCU');

-- 插入 ECU (中央网关，走 DoIP 以太网)
INSERT INTO odx_ecu (id, model_id, ecu_code, ecu_name, tx_id, rx_id, protocol, sec_algo_ref)
VALUES (2, 1, 'GW_ETH_V1', 'GW 中央网关 (以太网)', '0x0E80', '0x1010', 'UDS_ON_DOIP', NULL);

-- 插入 4 条典型的诊断服务
-- 1. 读取 VIN 码 (常规透传)
INSERT INTO odx_diag_service (id, ecu_id, service_code, sub_function, service_name, display_name, category, request_raw_hex, response_id_hex, macro_type, required_session)
VALUES (101, 1, '0x22', 'F190', 'Read_VIN', '读取车辆识别码(VIN)', '识别信息', '22F190', '62F190', 'raw_uds', '01');

-- 2. 也是读取数据，读取电池温度（用来测试带有公式的解码）
INSERT INTO odx_diag_service (id, ecu_id, service_code, sub_function, service_name, display_name, category, request_raw_hex, response_id_hex, macro_type, required_session)
VALUES (102, 1, '0x22', 'F112', 'Read_BattTemp', '读取电池温度', '运行数据', '22F112', '62F112', 'raw_uds', '01');

-- 3. 切换扩展会话 (前置要求)
INSERT INTO odx_diag_service (id, ecu_id, service_code, sub_function, service_name, display_name, category, request_raw_hex, response_id_hex, macro_type, required_session)
VALUES (103, 1, '0x10', '03', 'DiagSession_Extended', '进入扩展会话', '会话控制', '1003', '5003', 'raw_uds', NULL);

-- 4. 安全访问 (宏拦截测试)
INSERT INTO odx_diag_service (id, ecu_id, service_code, sub_function, service_name, display_name, category, request_raw_hex, response_id_hex, macro_type, required_session)
VALUES (104, 1, '0x27', '01', 'SecAccess_Lv1', '请求安全解锁(Lv1)', '安全访问', '2701', '6701', 'macro_security', '03');

-- 插入对应服务的参数解码规则 (只给读取电池温度配置)
-- 假设返回数据为 62F1123B，偏移量为3（跳过62F112），长度1字节（8bit），公式：(raw - 40) 代表温度
INSERT INTO odx_param_codec (service_id, param_name, display_name, byte_offset, bit_length, data_type, formula, unit)
VALUES (102, 'BattTemp', '电池温度探针A', 3, 8, 'unsigned', 'raw * 1 - 40', '°C');

-- 插入 GW (DoIP) 的诊断服务 Mock 数据
-- 5. 读取网关软件版本（DoIP ECU，常规透传）
INSERT INTO odx_diag_service (id, ecu_id, service_code, sub_function, service_name, display_name, category, request_raw_hex, response_id_hex, macro_type, required_session)
VALUES (201, 2, '0x22', 'F195', 'Read_GW_SwVer', '读取网关软件版本号', '识别信息', '22F195', '62F195', 'raw_uds', '01');

-- 6. 读取网关硬件版本（DoIP ECU）
INSERT INTO odx_diag_service (id, ecu_id, service_code, sub_function, service_name, display_name, category, request_raw_hex, response_id_hex, macro_type, required_session)
VALUES (202, 2, '0x22', 'F191', 'Read_GW_HwVer', '读取网关硬件版本号', '识别信息', '22F191', '62F191', 'raw_uds', '01');

-- 配置完毕，可开始在代码里进行 SELECT 查询了！

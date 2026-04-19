-- OpenDOTA Flyway V3 迁移
-- 新增: odx_ecu.gateway_chain JSONB(ECU 网关依赖链,支撑 EcuScopeResolver 服务)
-- 引入日期: 2026-04-19(审阅闭环 P1-8)
-- REST §4.1.1 GET /vehicle/{vin}/ecu-scope?ecuName=BMS 查询本字段

ALTER TABLE odx_ecu
    ADD COLUMN IF NOT EXISTS gateway_chain JSONB DEFAULT '[]'::jsonb;

COMMENT ON COLUMN odx_ecu.gateway_chain IS
    'ECU 到 OBD 口之间的网关依赖链,按物理路由顺序,如 ["GW"] 表示 BMS 必须经 GW 网关路由。EcuScopeResolver 用本字段推导完整 ecuScope。空数组 [] 表示直连 OBD。';

-- 索引:Resolver 是高频查询(每次打开通道都调),ecu_code + model_id 作 lookup key
CREATE INDEX IF NOT EXISTS idx_odx_ecu_model_code
    ON odx_ecu(model_id, ecu_code);

-- OpenDOTA Flyway V6 迁移
-- 修复: condition_signal_catalog 加 tenant_id + RLS(v1.4 审阅 A8)
-- 引入日期: 2026-04-19
--
-- 背景:
--   v1.2 基线建表只挂 model_id,未带 tenant_id。OEM A 与 OEM B 若共用同一车型平台
--   (如 CHERY_EXEED 的 model_id=1),会跨租户共享白名单 → 权限隔离破裂。
--
--   现在 OEM 之间共用车型平台属于常态(Tier1 供应商卖给多家整车厂),必须在
--   signal_catalog 上做租户切片。
--
-- 迁移策略:
--   1. 加 tenant_id 列(nullable,先兼容历史数据)
--   2. 将既有行按 odx_vehicle_model.tenant_id 回填
--   3. NOT NULL 化
--   4. 唯一键改为 (tenant_id, model_id, signal_name, version)
--   5. 启用 RLS

-- Step 1: 加列(nullable)
ALTER TABLE condition_signal_catalog
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);

-- Step 2: 按 odx_vehicle_model.tenant_id 回填
UPDATE condition_signal_catalog c
SET tenant_id = m.tenant_id
FROM odx_vehicle_model m
WHERE c.model_id = m.id
  AND c.tenant_id IS NULL;

-- Step 3: NOT NULL(仅当全部回填成功才生效,否则本迁移失败)
ALTER TABLE condition_signal_catalog
    ALTER COLUMN tenant_id SET NOT NULL;

-- Step 4: 替换唯一约束
--   v1.2 旧约束:UNIQUE(model_id, signal_name, version)
--   新约束:      UNIQUE(tenant_id, model_id, signal_name, version)
-- PG 不支持 ALTER CONSTRAINT 重命名唯一,用 DROP + ADD
DO $$
DECLARE
    old_constraint_name TEXT;
BEGIN
    SELECT conname INTO old_constraint_name
    FROM pg_constraint
    WHERE conrelid = 'condition_signal_catalog'::regclass
      AND contype = 'u'
      AND pg_get_constraintdef(oid) LIKE '%model_id%signal_name%version%';
    IF old_constraint_name IS NOT NULL THEN
        EXECUTE 'ALTER TABLE condition_signal_catalog DROP CONSTRAINT ' || quote_ident(old_constraint_name);
    END IF;
END$$;

ALTER TABLE condition_signal_catalog
    ADD CONSTRAINT uq_signal_catalog_tenant_model_name_ver
        UNIQUE (tenant_id, model_id, signal_name, version);

-- Step 5: 替换查询索引
DROP INDEX IF EXISTS idx_signal_catalog_version;
CREATE INDEX IF NOT EXISTS idx_signal_catalog_tenant_version
    ON condition_signal_catalog(tenant_id, model_id, version);

-- Step 6: 启用 RLS
ALTER TABLE condition_signal_catalog ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_tenant_signal_catalog ON condition_signal_catalog;
CREATE POLICY rls_tenant_signal_catalog ON condition_signal_catalog
    USING (tenant_id = current_setting('app.tenant_id', true));

COMMENT ON COLUMN condition_signal_catalog.tenant_id IS
    'v1.4 新增:租户隔离键。跨 OEM 共享车型平台场景下避免白名单泄漏,与 RLS 策略配合';

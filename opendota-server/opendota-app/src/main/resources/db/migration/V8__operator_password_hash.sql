-- 操作者密码哈希(BCrypt),支撑 JWT 登录流程
ALTER TABLE operator ADD COLUMN password_hash VARCHAR(256);
COMMENT ON COLUMN operator.password_hash IS 'BCrypt 密码哈希,仅本地账号登录使用;SSO 联合认证时为 NULL';

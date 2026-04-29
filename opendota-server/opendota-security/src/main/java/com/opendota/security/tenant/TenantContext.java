package com.opendota.security.tenant;

/**
 * 租户上下文 ThreadLocal 持有器。
 * 由 JwtAuthenticationFilter 在请求进入时设置,请求结束时清除。
 * TenantRlsAspect 读取此值执行 SET LOCAL app.tenant_id。
 */
public final class TenantContext {

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(String tenantId) {
        TENANT_ID.set(tenantId);
    }

    /**
     * 获取当前租户 ID,未设置时返回 null。
     */
    public static String current() {
        return TENANT_ID.get();
    }

    public static void clear() {
        TENANT_ID.remove();
    }
}

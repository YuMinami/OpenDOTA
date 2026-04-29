package com.opendota.security.tenant;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 租户 RLS 切面:在 @Transactional 方法执行前注入 SET LOCAL app.tenant_id。
 * @Order(0) 确保在 Spring @Transactional 切面之前执行。
 */
@Aspect
@Component
@Order(0)
public class TenantRlsAspect {

    private static final Logger log = LoggerFactory.getLogger(TenantRlsAspect.class);

    private final JdbcTemplate jdbcTemplate;

    public TenantRlsAspect(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Around("@annotation(org.springframework.transaction.annotation.Transactional)")
    public Object injectTenantId(ProceedingJoinPoint pjp) throws Throwable {
        String tenantId = TenantContext.current();
        if (tenantId != null && !tenantId.isBlank()) {
            try {
                jdbcTemplate.execute("SELECT set_config('app.tenant_id', ?, true)",
                        (org.springframework.jdbc.core.PreparedStatementCallback<Void>) ps -> {
                            ps.setString(1, tenantId);
                            ps.execute();
                            return null;
                        });
            } catch (Exception e) {
                log.warn("设置 app.tenant_id 失败: {}", e.getMessage());
            }
        }
        return pjp.proceed();
    }
}

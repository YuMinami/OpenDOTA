package com.opendota.security.rbac;

import com.opendota.common.envelope.OperatorRole;
import com.opendota.common.security.OperatorPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * RBAC 权限检查服务,通过 SpEL 在 @PreAuthorize 中调用。
 * 用法: @PreAuthorize("@rbac.canExecute('channel_open', authentication, #req.vin, false)")
 *
 * <p>权限矩阵基于协议 §15.4.2。
 */
@Component("rbac")
public class RbacService {

    private static final Logger log = LoggerFactory.getLogger(RbacService.class);

    /**
     * 检查当前认证主体是否有权限执行指定动作。
     *
     * @param action         动作标识(channel_open, single_cmd, batch_cmd, script_cmd, task_create, firmware_flash 等)
     * @param authentication Spring Security 认证对象
     * @param vin            目标车辆 VIN(可为 null)
     * @param force          是否强制抢占
     * @return true 表示允许
     */
    public boolean canExecute(String action, Authentication authentication, String vin, Boolean force) {
        if (authentication == null || !(authentication.getPrincipal() instanceof OperatorPrincipal principal)) {
            log.warn("RBAC 拒绝:无认证信息 action={}", action);
            return false;
        }

        OperatorRole role = principal.role();

        // SYSTEM 角色绕过所有检查
        if (role == OperatorRole.SYSTEM) {
            return true;
        }

        boolean allowed = switch (action) {
            // 只读操作:所有角色允许
            case "odx_query", "sse_subscribe", "channel_status" -> true;

            // 标准诊断:engineer 及以上
            case "channel_open", "channel_close", "single_cmd" -> role.ordinal() >= OperatorRole.ENGINEER.ordinal();

            // 批量/脚本诊断:engineer 及以上
            case "batch_cmd", "script_cmd" -> role.ordinal() >= OperatorRole.ENGINEER.ordinal();

            // 任务管理:engineer 及以上
            case "task_create", "task_cancel", "task_pause", "task_resume" ->
                    role.ordinal() >= OperatorRole.ENGINEER.ordinal();

            // 高权限操作:senior_engineer 及以上
            case "force_preempt" -> role.canForcePreempt();

            // 管理操作:admin only
            case "firmware_flash", "odx_import", "operator_manage", "audit_export" ->
                    role == OperatorRole.ADMIN;

            // Maker-Checker 审批操作:admin only
            case "approval_approve", "approval_reject" -> role == OperatorRole.ADMIN;

            // 未知动作:默认拒绝
            default -> {
                log.warn("RBAC 未知动作: {}", action);
                yield false;
            }
        };

        if (!allowed) {
            log.info("RBAC 拒绝: operatorId={} role={} action={}", principal.operatorId(), role.wireName(), action);
        }

        // force=true 需要额外检查
        if (allowed && Boolean.TRUE.equals(force) && !role.canForcePreempt()) {
            log.info("RBAC 拒绝 force: operatorId={} role={} action={}", principal.operatorId(), role.wireName(), action);
            return false;
        }

        return allowed;
    }
}

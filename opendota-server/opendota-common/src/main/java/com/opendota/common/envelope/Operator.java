package com.opendota.common.envelope;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 操作者上下文(协议 §3.3)。
 *
 * <p>C2V 的所有 {@code *_cmd} / {@code *_cancel} / {@code *_pause} / {@code *_resume} /
 * {@code queue_*} / {@code task_*} / {@code channel_*} 报文必须携带;V2C 建议原样回填。
 * 车端主动事件(lifecycle / queue_status / condition_fired)可省略,由云端标记为 system。
 *
 * @param id       操作员唯一标识,与 JWT.sub 一致
 * @param role     操作员角色,用于车端兜底 RBAC 校验
 * @param tenantId 租户 ID,车端校验与证书 O 字段一致,不一致直接丢弃
 * @param ticketId 工单号(如召回编号),可空但强烈建议填
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Operator(
        String id,
        OperatorRole role,
        String tenantId,
        String ticketId) {

    /**
     * 系统账号:任务触发、车端主动事件、操作员离职 fallback 场景使用。
     */
    public static Operator system(String tenantId) {
        return new Operator("system", OperatorRole.SYSTEM, tenantId, null);
    }
}

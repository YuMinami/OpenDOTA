package com.opendota.security.model;

/**
 * 操作者账号状态(operator.status 列)。
 * 物理删除禁止,仅通过状态标记禁用。
 */
public enum OperatorStatus {
    ACTIVE,
    DISABLED,
    LOCKED
}

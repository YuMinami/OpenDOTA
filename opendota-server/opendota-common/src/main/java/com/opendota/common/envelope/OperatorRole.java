package com.opendota.common.envelope;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 操作者角色(协议 §15.4.1)。RBAC 权限矩阵见 §15.4.2。
 */
public enum OperatorRole {
    VIEWER("viewer"),
    ENGINEER("engineer"),
    SENIOR_ENGINEER("senior_engineer"),
    ADMIN("admin"),
    SYSTEM("system");

    private final String wireName;

    OperatorRole(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static OperatorRole of(String wireName) {
        for (OperatorRole r : values()) {
            if (r.wireName.equals(wireName)) {
                return r;
            }
        }
        throw new IllegalArgumentException("未知 OperatorRole: " + wireName);
    }

    /**
     * 权限判断:当前角色是否可以执行 {@code force=true} 的抢占动作(§10.7.1)。
     */
    public boolean canForcePreempt() {
        return this == SENIOR_ENGINEER || this == ADMIN;
    }
}

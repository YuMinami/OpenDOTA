package com.opendota.security.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 操作者-角色关联 JPA 实体,映射 operator_role 表。
 * 复合主键(operatorId, roleId)。
 */
@Entity
@Table(name = "operator_role")
@IdClass(OperatorRoleEntity.Pk.class)
public class OperatorRoleEntity {

    @Id
    @Column(name = "operator_id", length = 64)
    private String operatorId;

    @Id
    @Column(name = "role_id")
    private Integer roleId;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "granted_by", length = 64)
    private String grantedBy;

    protected OperatorRoleEntity() {}

    public OperatorRoleEntity(String operatorId, Integer roleId, String grantedBy) {
        this.operatorId = operatorId;
        this.roleId = roleId;
        this.grantedBy = grantedBy;
        this.grantedAt = Instant.now();
    }

    public String getOperatorId() { return operatorId; }
    public Integer getRoleId() { return roleId; }
    public Instant getGrantedAt() { return grantedAt; }
    public String getGrantedBy() { return grantedBy; }

    /**
     * 复合主键。
     */
    public static class Pk implements Serializable {
        private String operatorId;
        private Integer roleId;

        public Pk() {}

        public Pk(String operatorId, Integer roleId) {
            this.operatorId = operatorId;
            this.roleId = roleId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(operatorId, pk.operatorId) && Objects.equals(roleId, pk.roleId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(operatorId, roleId);
        }
    }
}

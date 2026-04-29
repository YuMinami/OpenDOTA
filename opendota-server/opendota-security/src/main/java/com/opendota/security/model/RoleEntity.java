package com.opendota.security.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 角色字典 JPA 实体,映射 role 表。
 */
@Entity
@Table(name = "role")
public class RoleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "role_name", length = 32, nullable = false, unique = true)
    private String roleName;

    @Column(name = "description", length = 256)
    private String description;

    protected RoleEntity() {}

    public RoleEntity(String roleName, String description) {
        this.roleName = roleName;
        this.description = description;
    }

    public Integer getId() { return id; }
    public String getRoleName() { return roleName; }
    public String getDescription() { return description; }
}

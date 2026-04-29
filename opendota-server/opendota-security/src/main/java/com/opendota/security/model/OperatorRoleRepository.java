package com.opendota.security.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OperatorRoleRepository extends JpaRepository<OperatorRoleEntity, OperatorRoleEntity.Pk> {

    List<OperatorRoleEntity> findByOperatorId(String operatorId);
}

package com.opendota.security.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OperatorRepository extends JpaRepository<OperatorEntity, String> {

    Optional<OperatorEntity> findByTenantIdAndEmail(String tenantId, String email);
}

package com.app.demo.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.demo.model.Tenant;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    List<Tenant> findAllByDeletedAtIsNull();

    List<Tenant> findAllByDeletedAtIsNotNull();

    Optional<Tenant> findByIdAndDeletedAtIsNull(UUID id);
}
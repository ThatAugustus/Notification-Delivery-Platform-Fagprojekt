package com.app.demo.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.demo.model.Tenant;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
}
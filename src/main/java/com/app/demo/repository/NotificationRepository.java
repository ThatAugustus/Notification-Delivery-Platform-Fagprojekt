package com.app.demo.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.demo.model.Notification;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    Optional<Notification> findByTenant_IdAndIdempotencyKey(UUID tenantId, String idempotencyKey);
    /*
        SELECT * FROM notifications 
        WHERE tenant_id = somethign AND idempotency_key = something;
     */
}
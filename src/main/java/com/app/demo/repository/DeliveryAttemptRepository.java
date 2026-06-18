package com.app.demo.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.demo.model.DeliveryAttempt;
import com.app.demo.model.enums.DeliveryAttemptStatus;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {
    boolean existsByNotification_IdAndStatus(UUID notificationId, DeliveryAttemptStatus status);

}
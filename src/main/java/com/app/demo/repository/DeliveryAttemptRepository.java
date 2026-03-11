package com.app.demo.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.demo.model.DeliveryAttempt;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

}
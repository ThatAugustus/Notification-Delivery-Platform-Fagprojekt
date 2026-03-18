package com.app.demo.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.app.demo.dto.NotificationRequest;
import com.app.demo.model.Notification;
import com.app.demo.model.Tenant;
import com.app.demo.service.NotificationService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping
    public ResponseEntity<?> createNotification(
            @AuthenticationPrincipal Tenant tenant,
            @Valid @RequestBody NotificationRequest request) {

        Notification saved = notificationService.createNotification(tenant, request);

        return ResponseEntity.status(202).body(Map.of(
            "id", saved.getId(),
            "status", saved.getStatus()
        ));
    }
}

package com.app.demo.controller;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.app.demo.dto.NotificationRequest;
import com.app.demo.dto.NotificationResponse;
import com.app.demo.model.Notification;
import com.app.demo.model.Tenant;
import com.app.demo.service.ApiKeyService;
import com.app.demo.service.NotificationService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final ApiKeyService apiKeyService;

    public NotificationController(NotificationService notificationService,
                                   ApiKeyService apiKeyService) {
        this.notificationService = notificationService;
        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    public ResponseEntity<?> createNotification(
            @RequestHeader("X-API-Key") String rawApiKey,
            @Valid @RequestBody NotificationRequest request) {

        Tenant tenant = apiKeyService.resolveTenant(rawApiKey);
        Notification saved = notificationService.createNotification(tenant, request);
        return ResponseEntity.status(202).body(Map.of("id", saved.getId(), "status", "ACCEPTED"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<NotificationResponse> getNotification(
            @RequestHeader("X-API-Key") String rawApiKey,
            @PathVariable UUID id) {

        Tenant tenant = apiKeyService.resolveTenant(rawApiKey);
        Notification notification = notificationService.getNotification(tenant, id);

        NotificationResponse response = new NotificationResponse(
                notification.getId(),
                notification.getChannel().name(),
                notification.getRecipient(),
                notification.getSubject(),
                notification.getStatus().name(),
                notification.getCreatedAt(),
                notification.getUpdatedAt()
        );

        return ResponseEntity.ok(response);
    }
}
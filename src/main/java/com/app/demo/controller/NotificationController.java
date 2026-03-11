package com.app.demo.controller;

// Receives HTTP requests, calls services, returns responses

import org.springframework.web.bind.annotation.RestController;

import com.app.demo.dto.NotificationRequest;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.ResponseEntity;
import com.app.demo.service.NotificationService;
import com.app.demo.model.Notification;
import com.app.demo.model.Tenant;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping // basic setup
    public ResponseEntity<?> createNotification(@RequestHeader("X-API-Key") String apiKey,
            @Valid @RequestBody NotificationRequest request) {
        // TODO: look up Tenant by apiKey (e.g. tenantService.findByApiKey(apiKey))
        Tenant tenant = null;

        Notification saved = notificationService.createNotification(tenant, request);
        // ...
        return ResponseEntity.status(202).body(Map.of("id", saved.getId(), "status", "ACCEPTED"));
    }
}

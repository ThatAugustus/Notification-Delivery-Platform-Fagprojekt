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
import com.app.demo.service.TenantRateLimiterService;

import jakarta.validation.Valid;

// @RestController — Marks this class as an HTTP endpoint handler. Return values become JSON automatically.
// @RequestMapping — All endpoints in this class start with "/api/v1/notifications".
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final ApiKeyService apiKeyService;
    private final TenantRateLimiterService rateLimiterService;

    // Constructor injection — Spring auto-wires these dependencies when creating the controller.
    public NotificationController(NotificationService notificationService,
                                   ApiKeyService apiKeyService,
                                   TenantRateLimiterService rateLimiterService) {
        this.notificationService = notificationService;
        this.apiKeyService = apiKeyService;
        this.rateLimiterService = rateLimiterService;
    }

    // POST /api/v1/notifications — Submit a notification for delivery.
    @PostMapping
    public ResponseEntity<?> createNotification(
            @RequestHeader("X-API-Key") String rawApiKey, // @RequestHeader — Extracts the "X-API-Key" value from the HTTP header.
            // @RequestBody  — Deserializes the JSON body into a NotificationRequest object.
            // @Valid         — Triggers validation (@NotNull/@NotBlank) on the DTO before our code runs.
            @Valid @RequestBody NotificationRequest request) { 
            
        Tenant tenant = apiKeyService.resolveTenant(rawApiKey);       // Authenticate: hash key → DB lookup → get Tenant
        rateLimiterService.assertAllowed(tenant.getId());             // Enforce per-tenant rate limit (throws RateLimitExceededException -> 429)
        
        Notification saved = notificationService.createNotification(tenant, request);  // Save notification + outbox event
        
        // Returns 202 (Accepted) because delivery happens asynchronously — the notification is queued, not sent yet.
        return ResponseEntity.status(202).body(Map.of("id", saved.getId(), "status", "ACCEPTED"));
    }

    // GET /api/v1/notifications/{id} — Check delivery status of a notification.
    // @PathVariable — Extracts {id} from the URL (e.g. /notifications/c320f904-... → id = c320f904-...).
    // Tenant isolation: a tenant can only see their own notifications.
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
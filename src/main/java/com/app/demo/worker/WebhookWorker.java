package com.app.demo.worker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.app.demo.dto.NotificationPayload;
import com.app.demo.model.Notification;
import com.app.demo.repository.DeliveryAttemptRepository;
import com.app.demo.repository.NotificationRepository;
import com.app.demo.retry.RetryPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class WebhookWorker extends BaseNotificationWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookWorker.class);

    private final HttpClient httpClient;

    public WebhookWorker(
            ObjectMapper objectMapper,
            NotificationRepository notificationRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            RetryPolicy retryPolicy) {

        super(objectMapper, notificationRepository, deliveryAttemptRepository, retryPolicy);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @RabbitListener(queues = "webhook-queue")
    public void listen(Message message) {
        super.processMessage(message);
    }

    @Override
    protected void deliver(NotificationPayload payload, Notification notification) throws Exception {
        String webhookUrl = payload.getWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalArgumentException("Webhook URL is missing for notification " + payload.getNotificationId());
        }

        // Build the JSON body that the receiver will get
        String body = objectMapper.writeValueAsString(new WebhookBody(
                payload.getNotificationId().toString(),
                payload.getRecipient(),
                payload.getSubject(),
                payload.getContent()
        ));

        // Build the HTTP request
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        // Sign the body with HMAC-SHA256 if the tenant has a webhook secret
        String secret = payload.getWebhookSecret();
        if (secret != null && !secret.isBlank()) {
            String signature = computeHmac(body, secret);
            requestBuilder.header("X-Webhook-Signature", signature);
        }

        // Send the request
        HttpResponse<String> response = httpClient.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString()
        );

        // Check response — anything outside 2xx is a failure
        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new RuntimeException("Webhook returned HTTP " + statusCode + ": " + response.body());
        }

        log.info("Webhook delivered to {} with status {}", webhookUrl, statusCode);
    }

    private String computeHmac(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));

        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private record WebhookBody(
            String notificationId,
            String recipient,
            String subject,
            String content
    ) {}
}
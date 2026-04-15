package com.app.demo;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import com.app.demo.email.MockEmailProvider;
import com.app.demo.model.enums.NotificationStatus;
import com.app.demo.repository.DeliveryAttemptRepository;
import com.app.demo.repository.NotificationRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class NotificationFlowTest extends BaseIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;

    @Autowired
    private MockEmailProvider mockEmailProvider;

    private static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(0);
        wireMock.start();
        WireMock.configureFor(wireMock.port());
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @BeforeEach
    void resetMocks() {
        mockEmailProvider.clear();
        wireMock.resetAll();
    }


    @Test
    @DisplayName("Email notification: accepted → queued → delivered")
    void emailNotification_fullFlow() {
        // happy path for email delivery
        String idempotencyKey = "email-flow-" + UUID.randomUUID();
        String body = """
            {
                "channel": "EMAIL",
                "recipient": "flow-test@example.com",
                "subject": "Flow test",
                "content": "Testing full email delivery pipeline",
                "idempotencyKey": "%s"
            }
            """.formatted(idempotencyKey);

        var response = restTemplate.exchange(
                "/api/v1/notifications",
                HttpMethod.POST,
                new HttpEntity<>(body, authHeaders()),
                Map.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID notificationId = UUID.fromString(response.getBody().get("id").toString());

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            var notification = notificationRepository.findById(notificationId).orElseThrow();
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        });

        assertThat(mockEmailProvider.getSentEmails())
                .anyMatch(email -> email.to().equals("flow-test@example.com")
                        && email.subject().equals("Flow test"));

        var attempts = deliveryAttemptRepository.findAll().stream()
                .filter(a -> a.getNotification().getId().equals(notificationId))
                .toList();
        assertThat(attempts).hasSize(1);
        assertThat(attempts.getFirst().getStatus().name()).isEqualTo("SUCCESS");
        assertThat(attempts.getFirst().getDurationMs()).isPositive();
    }


    @Test
    @DisplayName("Same idempotency key → returns same notification, no duplicate")
    void idempotency_duplicateReturnsExisting() {
        // same idempotency key should not create a second notification
        String idempotencyKey = "idem-test-" + UUID.randomUUID();
        String body = """
            {
                "channel": "EMAIL",
                "recipient": "idem@example.com",
                "subject": "Idempotency",
                "content": "Should not create duplicate",
                "idempotencyKey": "%s"
            }
            """.formatted(idempotencyKey);

        var first = restTemplate.exchange(
                "/api/v1/notifications",
                HttpMethod.POST,
                new HttpEntity<>(body, authHeaders()),
                Map.class
        );
        var second = restTemplate.exchange(
                "/api/v1/notifications",
                HttpMethod.POST,
                new HttpEntity<>(body, authHeaders()),
                Map.class
        );

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
    }


    @Test
    @DisplayName("Webhook notification: delivered with HMAC signature")
    void webhookNotification_deliveredWithHmac() {
        // webhook call should be delivered and signed
        wireMock.stubFor(post(urlEqualTo("/webhook/receive"))
                .willReturn(aResponse().withStatus(200)));

        String idempotencyKey = "webhook-flow-" + UUID.randomUUID();
        String body = """
            {
                "channel": "WEBHOOK",
                "recipient": "webhook-consumer",
                "subject": "Webhook test",
                "content": "Payload for webhook",
                "idempotencyKey": "%s",
                "webhookUrl": "%s/webhook/receive"
            }
            """.formatted(idempotencyKey, wireMock.baseUrl());

        var response = restTemplate.exchange(
                "/api/v1/notifications",
                HttpMethod.POST,
                new HttpEntity<>(body, authHeaders()),
                Map.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID notificationId = UUID.fromString(response.getBody().get("id").toString());

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            var notification = notificationRepository.findById(notificationId).orElseThrow();
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        });

        wireMock.verify(1, postRequestedFor(urlEqualTo("/webhook/receive")));

        var requests = wireMock.findAll(postRequestedFor(urlEqualTo("/webhook/receive")));
        String signatureHeader = requests.getFirst().getHeader("X-Webhook-Signature");
        assertThat(signatureHeader).isNotNull().isNotBlank();

        String requestBody = requests.getFirst().getBodyAsString();
        String expectedSignature = computeHmac(requestBody, "test-webhook-secret-123");
        assertThat(signatureHeader).isEqualTo(expectedSignature);
    }


    @Test
    @DisplayName("Webhook fails once, retries, then succeeds")
    void retryFlow_failThenSucceed() {
        // first attempt fails, retry should recover
        wireMock.stubFor(post(urlEqualTo("/webhook/retry"))
                .inScenario("retry-test")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500).withBody("Server Error"))
                .willSetStateTo("SHOULD_SUCCEED"));

        wireMock.stubFor(post(urlEqualTo("/webhook/retry"))
                .inScenario("retry-test")
                .whenScenarioStateIs("SHOULD_SUCCEED")
                .willReturn(aResponse().withStatus(200)));

        String idempotencyKey = "retry-flow-" + UUID.randomUUID();
        String body = """
            {
                "channel": "WEBHOOK",
                "recipient": "retry-consumer",
                "subject": "Retry test",
                "content": "Should fail once then succeed",
                "idempotencyKey": "%s",
                "webhookUrl": "%s/webhook/retry"
            }
            """.formatted(idempotencyKey, wireMock.baseUrl());

        var response = restTemplate.exchange(
                "/api/v1/notifications",
                HttpMethod.POST,
                new HttpEntity<>(body, authHeaders()),
                Map.class
        );
        UUID notificationId = UUID.fromString(response.getBody().get("id").toString());

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            var notification = notificationRepository.findById(notificationId).orElseThrow();
            assertThat(notification.getStatus()).isIn(
                    NotificationStatus.RETRY_SCHEDULED,
                    NotificationStatus.QUEUED,
                    NotificationStatus.PROCESSING,
                    NotificationStatus.DELIVERED
            );
            assertThat(notification.getRetryCount()).isGreaterThanOrEqualTo(1);
        });

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            var notification = notificationRepository.findById(notificationId).orElseThrow();
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        });

        wireMock.verify(2, postRequestedFor(urlEqualTo("/webhook/retry")));

        var attempts = deliveryAttemptRepository.findAll().stream()
                .filter(a -> a.getNotification().getId().equals(notificationId))
                .toList();
        assertThat(attempts).hasSizeGreaterThanOrEqualTo(2);
        assertThat(attempts.stream().anyMatch(a -> a.getStatus().name().equals("FAILED"))).isTrue();
        assertThat(attempts.stream().anyMatch(a -> a.getStatus().name().equals("SUCCESS"))).isTrue();
    }


    private String computeHmac(String body, String secret) {
        try {
            // same algorithm as production webhook signing
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed in test", e);
        }
    }
}

package com.app.demo;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.app.demo.email.MockEmailProvider;
import com.app.demo.model.enums.NotificationStatus;
import com.app.demo.repository.NotificationRepository;

class TenantSoftDeleteDeliveryTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private MockEmailProvider mockEmailProvider;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @BeforeEach
    void resetEmail() {
        mockEmailProvider.clear();
    }

    @Test
    @DisplayName("Soft delete of an idle tenant refuses new work and removes its queues")
    void softDelete_stopsIdleTenantCleanly() {
        String tenantId = createTenant();
        String rawKey = createKey(tenantId, "active");

        String emailQueue = "email-queue." + tenantId;
        String webhookQueue = "webhook-queue." + tenantId;

        String recipient = "active-" + UUID.randomUUID() + "@example.com";
        UUID notificationId = submitEmail(rawKey, recipient);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            var n = notificationRepository.findById(notificationId).orElseThrow();
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        });
        assertThat(mockEmailProvider.getSentEmails())
                .anyMatch(e -> e.to().equals(recipient));

        assertThat(rabbitAdmin.getQueueProperties(emailQueue))
                .as("email queue should exist while the tenant is active")
                .isNotNull();

        ResponseEntity<Void> delete = restTemplate.exchange(
                "/api/v1/admin/tenants/" + tenantId,
                HttpMethod.DELETE,
                new HttpEntity<>(adminHeaders()),
                Void.class);
        assertThat(delete.getStatusCode().is2xxSuccessful())
                .as("soft delete should succeed")
                .isTrue();

        ResponseEntity<Map<String, Object>> afterDelete = restTemplate.exchange(
                "/api/v1/notifications",
                HttpMethod.POST,
                new HttpEntity<>(emailBody(recipient), tenantHeaders(rawKey)),
                MAP_TYPE);
        assertThat(afterDelete.getStatusCode())
                .as("a soft-deleted tenant must not be able to submit new notifications")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map<String, Object>> get = restTemplate.exchange(
                "/api/v1/admin/tenants/" + tenantId,
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                MAP_TYPE);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(rabbitAdmin.getQueueProperties(emailQueue))
                    .as("email queue should be deleted after soft delete")
                    .isNull();
            assertThat(rabbitAdmin.getQueueProperties(webhookQueue))
                    .as("webhook queue should be deleted after soft delete")
                    .isNull();
        });
    }

    @Test
    @DisplayName("Soft delete under load drains in-flight work: every accepted notification is delivered exactly once, then the queues are removed")
    void softDelete_underLoad_deliversAllInFlightWorkWithoutDuplicates() throws InterruptedException {
        String tenantId = createTenant();
        String rawKey = createKey(tenantId, "busy");

        String emailQueue = "email-queue." + tenantId;
        String webhookQueue = "webhook-queue." + tenantId;

        // keep firing notifications until the key gets rejected
        List<Submitted> accepted = new CopyOnWriteArrayList<>();
        AtomicBoolean refused = new AtomicBoolean(false);
        AtomicBoolean stop = new AtomicBoolean(false);

        Thread submitter = new Thread(() -> {
            while (!stop.get()) {
                String recipient = "busy-" + UUID.randomUUID() + "@example.com";
                ResponseEntity<Map<String, Object>> res = restTemplate.exchange(
                        "/api/v1/notifications",
                        HttpMethod.POST,
                        new HttpEntity<>(emailBody(recipient), tenantHeaders(rawKey)),
                        MAP_TYPE);
                if (res.getStatusCode() == HttpStatus.ACCEPTED) {
                    accepted.add(new Submitted(
                            UUID.fromString(res.getBody().get("id").toString()), recipient));
                } else if (res.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                    refused.set(true);
                    break;
                }
            }
        });
        submitter.start();

        // wait until stuff is actually being processed, then delete while it's mid-flight
        await().atMost(15, TimeUnit.SECONDS).until(() -> accepted.size() >= 25);

        ResponseEntity<Void> delete = restTemplate.exchange(
                "/api/v1/admin/tenants/" + tenantId,
                HttpMethod.DELETE,
                new HttpEntity<>(adminHeaders()),
                Void.class);
        assertThat(delete.getStatusCode().is2xxSuccessful())
                .as("soft delete of a busy tenant should succeed")
                .isTrue();

        // once we get a 401 the delete has taken effect mid-send
        await().atMost(15, TimeUnit.SECONDS).untilTrue(refused);
        stop.set(true);
        submitter.join(TimeUnit.SECONDS.toMillis(15));

        assertThat(accepted)
                .as("the tenant should have had notifications in flight when it was deleted")
                .isNotEmpty();

        ResponseEntity<Map<String, Object>> afterDelete = restTemplate.exchange(
                "/api/v1/notifications",
                HttpMethod.POST,
                new HttpEntity<>(emailBody("after-delete@example.com"), tenantHeaders(rawKey)),
                MAP_TYPE);
        assertThat(afterDelete.getStatusCode())
                .as("a soft-deleted tenant must not be able to submit new notifications")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // anything accepted before the delete should still get delivered, not dropped
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(accepted).allSatisfy(s -> {
                    var n = notificationRepository.findById(s.id()).orElseThrow();
                    assertThat(n.getStatus())
                            .as("notification %s should have been delivered, was %s", s.id(), n.getStatus())
                            .isEqualTo(NotificationStatus.DELIVERED);
                }));

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(rabbitAdmin.getQueueProperties(emailQueue)).isNull();
            assertThat(rabbitAdmin.getQueueProperties(webhookQueue)).isNull();
        });

        List<String> acceptedRecipients = accepted.stream().map(Submitted::recipient).toList();
        List<String> deliveredTo = mockEmailProvider.getSentEmails().stream()
                .map(e -> e.to())
                .filter(acceptedRecipients::contains)
                .toList();
        assertThat(deliveredTo)
                .as("no in-flight recipient should have received a duplicate email")
                .doesNotHaveDuplicates();
    }

    private record Submitted(UUID id, String recipient) {
    }

    private UUID submitEmail(String rawKey, String recipient) {
        ResponseEntity<Map<String, Object>> submit = restTemplate.exchange(
                "/api/v1/notifications",
                HttpMethod.POST,
                new HttpEntity<>(emailBody(recipient), tenantHeaders(rawKey)),
                MAP_TYPE);
        assertThat(submit.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return UUID.fromString(submit.getBody().get("id").toString());
    }

    private String emailBody(String recipient) {
        return """
                {
                    "channel": "EMAIL",
                    "recipient": "%s",
                    "subject": "Soft delete delivery test",
                    "content": "Testing soft delete behaviour",
                    "idempotencyKey": "soft-delete-%s"
                }
                """.formatted(recipient, UUID.randomUUID());
    }

    private String createTenant() {
        String body = """
                { "name": "Soft Delete Tenant %s" }
                """.formatted(UUID.randomUUID());
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/admin/tenants",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                MAP_TYPE);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("tenant create failed: %s %s", response.getStatusCode(), response.getBody())
                .isTrue();
        return response.getBody().get("id").toString();
    }

    private String createKey(String tenantId, String name) {
        String body = """
                { "name": "%s" }
                """.formatted(name);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + tenantId + "/api-keys",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                MAP_TYPE);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("key create failed: %s %s", response.getStatusCode(), response.getBody())
                .isTrue();
        return response.getBody().get("rawKey").toString();
    }
}

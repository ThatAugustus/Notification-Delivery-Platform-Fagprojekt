package com.app.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ApiKeyAdminApiTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};


    @Nested
    @DisplayName("POST /api/v1/admin/tenants/{tenantId}/api-keys")
    class CreateKeyTests {

        @Test
        @DisplayName("Valid request → 201 with raw key visible once")
        void createKey_returnsRawKeyOnce() {
            String tenantId = createTenant();

            String body = """
                { "name": "production" }
                """;

            var response = restTemplate.exchange(
                    "/api/v1/admin/tenants/" + tenantId + "/api-keys",
                    HttpMethod.POST,
                    new HttpEntity<>(body, adminHeaders()),
                    MAP_TYPE
            );

            // === FIX: show actual response body on failure ===
            assertThat(response.getStatusCode())
                    .as("Expected 201 Created, got: %s with body: %s",
                            response.getStatusCode(), response.getBody())
                    .isEqualTo(HttpStatus.CREATED);
            // === END FIX ===

            assertThat(response.getBody()).containsKey("rawKey");
            assertThat(response.getBody().get("rawKey")).asString().startsWith("ndp_");
            assertThat(response.getBody()).containsKey("warning");
            assertThat(response.getBody().get("prefix")).asString().hasSize(8);
        }

        @Test
        @DisplayName("List endpoint never returns raw key")
        void listKeys_neverExposesRawKey() {
            String tenantId = createTenant();
            createKey(tenantId, "prod");

            var response = restTemplate.exchange(
                    "/api/v1/admin/tenants/" + tenantId + "/api-keys",
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders()),
                    LIST_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(1);
            assertThat(response.getBody().get(0)).doesNotContainKey("rawKey");
            assertThat(response.getBody().get(0).get("active")).isEqualTo(true);
        }

        @Test
        @DisplayName("Create key for non-existent tenant → 404")
        void createKey_nonExistentTenant_returns404() {
            UUID fakeId = UUID.randomUUID();

            String body = """
                { "name": "prod" }
                """;

            var response = restTemplate.exchange(
                    "/api/v1/admin/tenants/" + fakeId + "/api-keys",
                    HttpMethod.POST,
                    new HttpEntity<>(body, adminHeaders()),
                    MAP_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }


    @Nested
    @DisplayName("End-to-end: new key can send a notification")
    class EndToEndTests {

        @Test
        @DisplayName("Newly created key authenticates successfully against POST /notifications")
        void newKey_canSendNotification() {
            String tenantId = createTenant();
            String rawKey = createKey(tenantId, "prod");

            String notifBody = """
                {
                    "channel": "EMAIL",
                    "recipient": "new-key-test@example.com",
                    "subject": "Testing fresh key",
                    "content": "This should succeed",
                    "idempotencyKey": "new-key-%s"
                }
                """.formatted(UUID.randomUUID());

            var response = restTemplate.exchange(
                    "/api/v1/notifications",
                    HttpMethod.POST,
                    new HttpEntity<>(notifBody, tenantHeaders(rawKey)),
                    MAP_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }

        @Test
        @DisplayName("Revoked key is rejected with 401")
        void revokedKey_isRejected() {
            String tenantId = createTenant();
            String rawKey = createKey(tenantId, "to-revoke");
            String keyId = findKeyIdFor(tenantId);

            var revokeResponse = restTemplate.exchange(
                    "/api/v1/admin/tenants/" + tenantId + "/api-keys/" + keyId,
                    HttpMethod.DELETE,
                    new HttpEntity<>(adminHeaders()),
                    Void.class
            );
            assertThat(revokeResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            String notifBody = """
                {
                    "channel": "EMAIL",
                    "recipient": "revoked-test@example.com",
                    "subject": "Should fail",
                    "content": "Key is revoked",
                    "idempotencyKey": "revoked-%s"
                }
                """.formatted(UUID.randomUUID());

            var notifResponse = restTemplate.exchange(
                    "/api/v1/notifications",
                    HttpMethod.POST,
                    new HttpEntity<>(notifBody, tenantHeaders(rawKey)),
                    MAP_TYPE
            );

            assertThat(notifResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Key for soft-deleted tenant is rejected with 401")
        void keyForSoftDeletedTenant_isRejected() {
            String tenantId = createTenant();
            String rawKey = createKey(tenantId, "doomed");

            restTemplate.exchange(
                    "/api/v1/admin/tenants/" + tenantId,
                    HttpMethod.DELETE,
                    new HttpEntity<>(adminHeaders()),
                    Void.class
            );

            String notifBody = """
                {
                    "channel": "EMAIL",
                    "recipient": "deleted-tenant@example.com",
                    "subject": "Should fail",
                    "content": "Tenant is gone",
                    "idempotencyKey": "deleted-tenant-%s"
                }
                """.formatted(UUID.randomUUID());

            var response = restTemplate.exchange(
                    "/api/v1/notifications",
                    HttpMethod.POST,
                    new HttpEntity<>(notifBody, tenantHeaders(rawKey)),
                    MAP_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }


    // --- helpers with diagnostic output ---

    private String createTenant() {
        String body = """
            { "name": "API Key Test Tenant %s" }
            """.formatted(UUID.randomUUID());

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/admin/tenants",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                MAP_TYPE
        );

        // === FIX: fail with diagnostic info instead of silent NPE ===
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError("Failed to create tenant. Status: " + response.getStatusCode()
                    + " Body: " + response.getBody());
        }
        Object id = response.getBody() == null ? null : response.getBody().get("id");
        if (id == null) {
            throw new AssertionError("Tenant response missing 'id' field. Body: " + response.getBody());
        }
        return id.toString();
        // === END FIX ===
    }

    private String createKey(String tenantId, String name) {
        String body = """
            { "name": "%s" }
            """.formatted(name);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + tenantId + "/api-keys",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                MAP_TYPE
        );

        // === FIX: surface actual server error instead of NPE ===
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError("Failed to create API key for tenant " + tenantId
                    + ". Status: " + response.getStatusCode()
                    + " Body: " + response.getBody());
        }
        Object rawKey = response.getBody() == null ? null : response.getBody().get("rawKey");
        if (rawKey == null) {
            throw new AssertionError("API key response missing 'rawKey' field. Body: " + response.getBody());
        }
        return rawKey.toString();
        // === END FIX ===
    }

    private String findKeyIdFor(String tenantId) {
        var response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + tenantId + "/api-keys",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                LIST_TYPE
        );
        return response.getBody().get(0).get("id").toString();
    }
}

package com.app.demo;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationApiTest extends BaseIntegrationTest {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };


    @Nested
    @DisplayName("Authentication")
    class AuthTests {

        @Test
        @DisplayName("Valid API key → 202 Accepted")
        void validApiKey_returnsAccepted() {
            // valid key should accept the request
            String body = """
                {
                    "channel": "EMAIL",
                    "recipient": "auth-test@example.com",
                    "subject": "Auth test",
                    "content": "Testing valid key",
                    "idempotencyKey": "auth-valid-%s"
                }
                """.formatted(UUID.randomUUID());

            var response = restTemplate.exchange(
                    "/api/v1/notifications",
                    HttpMethod.POST,
                    new HttpEntity<>(body, authHeaders()),
                    MAP_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).containsKey("id");
            assertThat(response.getBody().get("status")).isEqualTo("ACCEPTED");
        }

        @Test
        @DisplayName("Invalid API key → 401 Unauthorized")
        void invalidApiKey_returnsUnauthorized() {
            // wrong key should be rejected
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-Key", INVALID_API_KEY);
            headers.set("Content-Type", "application/json");

            String body = """
                {
                    "channel": "EMAIL",
                    "recipient": "test@example.com",
                    "subject": "Test",
                    "content": "Body",
                    "idempotencyKey": "auth-invalid-%s"
                }
                """.formatted(UUID.randomUUID());

            var response = restTemplate.exchange(
                    "/api/v1/notifications",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    MAP_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Missing API key header → 401 Unauthorized")
        void missingApiKeyHeader_returnsUnauthorized() {
            // missing key header should be rejected
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");

            String body = """
                {
                    "channel": "EMAIL",
                    "recipient": "test@example.com",
                    "subject": "Test",
                    "content": "Body",
                    "idempotencyKey": "auth-missing-%s"
                }
                """.formatted(UUID.randomUUID());

            var response = restTemplate.exchange(
                    "/api/v1/notifications",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    MAP_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }


    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("Missing recipient → 400 Bad Request")
        void missingRecipient_returnsBadRequest() {
            // recipient is required
            String body = """
                {
                    "channel": "EMAIL",
                    "subject": "No recipient",
                    "content": "Body",
                    "idempotencyKey": "val-no-recipient-%s"
                }
                """.formatted(UUID.randomUUID());

            var response = restTemplate.exchange(
                    "/api/v1/notifications",
                    HttpMethod.POST,
                    new HttpEntity<>(body, authHeaders()),
                    MAP_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Missing content → 400 Bad Request")
        void missingContent_returnsBadRequest() {
            // content is required
            String body = """
                {
                    "channel": "EMAIL",
                    "recipient": "test@example.com",
                    "subject": "No content",
                    "idempotencyKey": "val-no-content-%s"
                }
                """.formatted(UUID.randomUUID());

            var response = restTemplate.exchange(
                    "/api/v1/notifications",
                    HttpMethod.POST,
                    new HttpEntity<>(body, authHeaders()),
                    MAP_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Missing idempotencyKey → 400 Bad Request")
        void missingIdempotencyKey_returnsBadRequest() {
            // idempotency key is required
            String body = """
                {
                    "channel": "EMAIL",
                    "recipient": "test@example.com",
                    "content": "Body"
                }
                """;

            var response = restTemplate.exchange(
                    "/api/v1/notifications",
                    HttpMethod.POST,
                    new HttpEntity<>(body, authHeaders()),
                    MAP_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Webhook without URL → 400 Bad Request")
        void webhookWithoutUrl_returnsBadRequest() {
            // webhook channel must include a webhook url
            String body = """
                {
                    "channel": "WEBHOOK",
                    "recipient": "webhook-consumer",
                    "content": "Payload",
                    "idempotencyKey": "val-no-webhook-url-%s"
                }
                """.formatted(UUID.randomUUID());

            var response = restTemplate.exchange(
                    "/api/v1/notifications",
                    HttpMethod.POST,
                    new HttpEntity<>(body, authHeaders()),
                    MAP_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }


    @Nested
    @DisplayName("GET /notifications/{id}")
    class GetTests {

        @Test
        @DisplayName("Existing notification → 200 with correct fields")
        void getExistingNotification_returnsOk() {
            // create one and fetch it back
            String idempotencyKey = "get-test-" + UUID.randomUUID();
            String createBody = """
                {
                    "channel": "EMAIL",
                    "recipient": "get-test@example.com",
                    "subject": "Get test",
                    "content": "Testing GET endpoint",
                    "idempotencyKey": "%s"
                }
                """.formatted(idempotencyKey);

            var createResponse = restTemplate.exchange(
                    "/api/v1/notifications",
                    HttpMethod.POST,
                    new HttpEntity<>(createBody, authHeaders()),
                    MAP_TYPE
            );
            String id = createResponse.getBody().get("id").toString();

            var getResponse = restTemplate.exchange(
                    "/api/v1/notifications/" + id,
                    HttpMethod.GET,
                    new HttpEntity<>(authHeaders()),
                    MAP_TYPE
            );

            assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(getResponse.getBody().get("id")).isEqualTo(id);
            assertThat(getResponse.getBody().get("channel")).isEqualTo("EMAIL");
            assertThat(getResponse.getBody().get("recipient")).isEqualTo("get-test@example.com");
        }

        @Test
        @DisplayName("Non-existent notification → 404")
        void getNonExistent_returnsNotFound() {
            // random id should return not found
            UUID fakeId = UUID.randomUUID();

            var response = restTemplate.exchange(
                    "/api/v1/notifications/" + fakeId,
                    HttpMethod.GET,
                    new HttpEntity<>(authHeaders()),
                    MAP_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}

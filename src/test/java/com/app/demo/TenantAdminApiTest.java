
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

class TenantAdminApiTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};


    @Nested
    @DisplayName("Admin authentication")
    class AdminAuthTests {

        // === FIX: use MAP_TYPE for auth-failure tests ===
        // When auth fails, GlobalExceptionHandler returns an error object:
        //   {"status": 401, "error": "Unauthorized", "message": "...", "timestamp": "..."}
        // That's a JSON object, not a list. Trying to deserialize it as List<Map>
        // throws a parse error BEFORE we can check the status code.

        @Test
        @DisplayName("Missing admin key → 401")
        void missingAdminKey_returnsUnauthorized() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");

            var response = restTemplate.exchange(
                    "/api/v1/admin/tenants",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    MAP_TYPE  // <-- changed from LIST_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Wrong admin key → 401")
        void wrongAdminKey_returnsUnauthorized() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Admin-Key", INVALID_ADMIN_KEY);
            headers.set("Content-Type", "application/json");

            var response = restTemplate.exchange(
                    "/api/v1/admin/tenants",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    MAP_TYPE  // <-- changed from LIST_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Tenant API key cannot access admin endpoints → 401")
        void tenantApiKey_isRejectedByAdminEndpoints() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-Key", VALID_API_KEY);
            headers.set("Content-Type", "application/json");

            var response = restTemplate.exchange(
                    "/api/v1/admin/tenants",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    MAP_TYPE  // <-- changed from LIST_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        // === END FIX ===
    }


    @Nested
    @DisplayName("POST /api/v1/admin/tenants")
    class CreateTenantTests {

        @Test
        @DisplayName("Valid request → 201 with generated id and webhook_secret")
        void createTenant_returns201() {
            String body = """
                {
                    "name": "Acme Corp %s",
                    "defaultFromEmail": "noreply@acme.com"
                }
                """.formatted(UUID.randomUUID());

            var response = restTemplate.exchange(
                    "/api/v1/admin/tenants",
                    HttpMethod.POST,
                    new HttpEntity<>(body, adminHeaders()),
                    MAP_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).containsKey("id");
            assertThat(response.getBody()).containsKey("webhookSecret");
            assertThat(response.getBody().get("webhookSecret")).asString().isNotBlank();
            assertThat(response.getBody().get("defaultFromEmail")).isEqualTo("noreply@acme.com");
            assertThat(response.getBody().get("deletedAt")).isNull();
        }

        @Test
        @DisplayName("Missing name → 400")
        void createTenant_missingName_returns400() {
            String body = """
                {
                    "defaultFromEmail": "noreply@acme.com"
                }
                """;

            var response = restTemplate.exchange(
                    "/api/v1/admin/tenants",
                    HttpMethod.POST,
                    new HttpEntity<>(body, adminHeaders()),
                    MAP_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Invalid email format → 400")
        void createTenant_invalidEmail_returns400() {
            String body = """
                {
                    "name": "Test",
                    "defaultFromEmail": "not-an-email"
                }
                """;

            var response = restTemplate.exchange(
                    "/api/v1/admin/tenants",
                    HttpMethod.POST,
                    new HttpEntity<>(body, adminHeaders()),
                    MAP_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }


    @Nested
    @DisplayName("GET /api/v1/admin/tenants")
    class ListTenantsTests {

        @Test
        @DisplayName("List includes newly created tenant")
        void listTenants_includesNewTenant() {
            String name = "List Test " + UUID.randomUUID();
            String body = """
                { "name": "%s" }
                """.formatted(name);

            restTemplate.exchange(
                    "/api/v1/admin/tenants",
                    HttpMethod.POST,
                    new HttpEntity<>(body, adminHeaders()),
                    MAP_TYPE
            );

            var listResponse = restTemplate.exchange(
                    "/api/v1/admin/tenants",
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders()),
                    LIST_TYPE
            );

            assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(listResponse.getBody())
                    .extracting(t -> t.get("name"))
                    .contains(name);
        }
    }


    @Nested
    @DisplayName("GET /api/v1/admin/tenants/{id}")
    class GetTenantTests {

        @Test
        @DisplayName("Existing tenant → 200")
        void getTenant_returnsOk() {
            String id = createTenant("Get Test " + UUID.randomUUID());

            var response = restTemplate.exchange(
                    "/api/v1/admin/tenants/" + id,
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders()),
                    MAP_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().get("id")).isEqualTo(id);
        }

        @Test
        @DisplayName("Non-existent tenant → 404")
        void getTenant_nonExistent_returns404() {
            UUID fakeId = UUID.randomUUID();

            var response = restTemplate.exchange(
                    "/api/v1/admin/tenants/" + fakeId,
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders()),
                    MAP_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }


    @Nested
    @DisplayName("PATCH /api/v1/admin/tenants/{id}")
    class UpdateTenantTests {

        @Test
        @DisplayName("Partial update only changes provided fields")
        void patchTenant_onlyChangesProvidedFields() {
            String id = createTenant("Original Name");

            String body = """
                { "defaultFromEmail": "updated@acme.com" }
                """;

            var response = restTemplate.exchange(
                    "/api/v1/admin/tenants/" + id,
                    HttpMethod.PATCH,
                    new HttpEntity<>(body, adminHeaders()),
                    MAP_TYPE
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().get("name")).isEqualTo("Original Name");
            assertThat(response.getBody().get("defaultFromEmail")).isEqualTo("updated@acme.com");
        }
    }


    @Nested
    @DisplayName("DELETE + POST /restore /api/v1/admin/tenants/{id}")
    class DeleteAndRestoreTests {

        @Test
        @DisplayName("Delete → 204, then GET → 404 (soft-deleted)")
        void deleteTenant_thenGet_returns404() {
            String id = createTenant("To Delete " + UUID.randomUUID());

            var deleteResponse = restTemplate.exchange(
                    "/api/v1/admin/tenants/" + id,
                    HttpMethod.DELETE,
                    new HttpEntity<>(adminHeaders()),
                    Void.class
            );
            assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            var getResponse = restTemplate.exchange(
                    "/api/v1/admin/tenants/" + id,
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders()),
                    MAP_TYPE
            );
            assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Deleted tenant is not in list output")
        void deleteTenant_notInList() {
            String name = "Hidden " + UUID.randomUUID();
            String id = createTenant(name);

            restTemplate.exchange(
                    "/api/v1/admin/tenants/" + id,
                    HttpMethod.DELETE,
                    new HttpEntity<>(adminHeaders()),
                    Void.class
            );

            var listResponse = restTemplate.exchange(
                    "/api/v1/admin/tenants",
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders()),
                    LIST_TYPE
            );

            assertThat(listResponse.getBody())
                    .extracting(t -> t.get("name"))
                    .doesNotContain(name);
        }

        @Test
        @DisplayName("Restore brings a soft-deleted tenant back")
        void restoreTenant_bringsItBack() {
            String id = createTenant("Restore Test " + UUID.randomUUID());

            restTemplate.exchange(
                    "/api/v1/admin/tenants/" + id,
                    HttpMethod.DELETE,
                    new HttpEntity<>(adminHeaders()),
                    Void.class
            );

            var restoreResponse = restTemplate.exchange(
                    "/api/v1/admin/tenants/" + id + "/restore",
                    HttpMethod.POST,
                    new HttpEntity<>(adminHeaders()),
                    MAP_TYPE
            );
            assertThat(restoreResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(restoreResponse.getBody().get("deletedAt")).isNull();

            var getResponse = restTemplate.exchange(
                    "/api/v1/admin/tenants/" + id,
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders()),
                    MAP_TYPE
            );
            assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }


    private String createTenant(String name) {
        String body = """
            { "name": "%s" }
            """.formatted(name);

        var response = restTemplate.exchange(
                "/api/v1/admin/tenants",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                MAP_TYPE
        );
        return response.getBody().get("id").toString();
    }
}

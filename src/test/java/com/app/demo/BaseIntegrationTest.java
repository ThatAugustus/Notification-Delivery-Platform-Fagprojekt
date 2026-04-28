package com.app.demo;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("notification_platform")
            .withUsername("test")
            .withPassword("test");

    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4-management");

    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    static {
        rabbit.start();
        redis.start();
        postgres.start();
    }

    @DynamicPropertySource
    static void configureContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort
    private int port;

    protected RestTemplate restTemplate;

    @BeforeEach
    void setupRestTemplate() {
        // Use Apache HttpClient 5 which supports PATCH (java.net.HttpURLConnection doesn't)
        HttpClient httpClient = HttpClientBuilder.create().build();
        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClient);

        RestTemplate template = new RestTemplate(factory);
        template.setUriTemplateHandler(new DefaultUriBuilderFactory("http://localhost:" + port));
        template.setErrorHandler(new NoOpResponseErrorHandler());
        this.restTemplate = template;
    }

    // Tenant-level API key (from Flyway V2/V4 seed data)
    protected static final String VALID_API_KEY = "my-test-key-123";
    protected static final String INVALID_API_KEY = "this-key-does-not-exist";

    // Admin auth constants (must match application-test.properties)
    protected static final String VALID_ADMIN_KEY = "test-admin-key";
    protected static final String INVALID_ADMIN_KEY = "wrong-admin-key";

    protected org.springframework.http.HttpHeaders authHeaders() {
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("X-API-Key", VALID_API_KEY);
        headers.set("Content-Type", "application/json");
        return headers;
    }

    protected org.springframework.http.HttpHeaders adminHeaders() {
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Admin-Key", VALID_ADMIN_KEY);
        headers.set("Content-Type", "application/json");
        return headers;
    }

    protected org.springframework.http.HttpHeaders tenantHeaders(String rawKey) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("X-API-Key", rawKey);
        headers.set("Content-Type", "application/json");
        return headers;
    }
}
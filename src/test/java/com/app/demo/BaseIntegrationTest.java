package com.app.demo;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.junit.jupiter.api.BeforeEach;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    // one shared infra setup for all integration tests
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("notification_platform")
            .withUsername("test")
            .withPassword("test");

    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4-management");

    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    static {
        // start once, keep ports stable during the test run
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
        // point all requests at the random Spring Boot test port
        RestTemplate template = new RestTemplate();
        template.setUriTemplateHandler(new DefaultUriBuilderFactory("http://localhost:" + port));
        template.setErrorHandler(new NoOpResponseErrorHandler());
        this.restTemplate = template;
    }

    protected static final String VALID_API_KEY = "my-test-key-123";
    protected static final String INVALID_API_KEY = "this-key-does-not-exist";

    protected org.springframework.http.HttpHeaders authHeaders() {
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("X-API-Key", VALID_API_KEY);
        headers.set("Content-Type", "application/json");
        return headers;
    }
}

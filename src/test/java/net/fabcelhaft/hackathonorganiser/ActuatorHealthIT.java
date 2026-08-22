package net.fabcelhaft.hackathonorganiser;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for the Actuator health endpoint (contract:
 * specs/001-spring-boot-infrastructure/contracts/health-endpoint.md).
 *
 * <p>Uses {@link WebTestClient} against a real, randomly-ported WebFlux server and a real
 * PostgreSQL instance supplied by Testcontainers. {@code @ServiceConnection} wires the container
 * directly into the R2DBC auto-configuration — no manual property overrides required.
 *
 * <p>{@code @AutoConfigureWebTestClient} is required as of Spring Boot 4.x: the WebTestClient
 * bean is no longer registered implicitly by {@code @SpringBootTest(RANDOM_PORT)}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Testcontainers
class ActuatorHealthIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @Autowired
    WebTestClient webTestClient;

    @Test
    void healthEndpointReturnsUpWithR2dbcIndicator() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.r2dbc.status").isEqualTo("UP");
    }
}

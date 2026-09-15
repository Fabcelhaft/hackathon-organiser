package net.fabcelhaft.hackathonorganiser.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Unit/component tests for {@link HttpDestinationSender} (contracts/delivery-transport.md "HTTP
 * POST Destination"; research.md §8) against a throwaway {@link HttpServer} — no mocking library
 * dependency is added for this, per research.md §8.
 */
class HttpDestinationSenderTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsAPostWithJsonContentTypeAndTheExactBody() throws IOException {
        CompletableFuture<String> receivedBody = new CompletableFuture<>();
        CompletableFuture<String> receivedContentType = new CompletableFuture<>();
        CompletableFuture<String> receivedMethod = new CompletableFuture<>();
        server = startServer(exchange -> {
            receivedMethod.complete(exchange.getRequestMethod());
            receivedContentType.complete(exchange.getRequestHeaders().getFirst("Content-Type"));
            receivedBody.complete(readBody(exchange));
            respond(exchange, 200);
        });

        HttpDestinationSender sender = new HttpDestinationSender();
        EventDestination destination = httpDestination(baseUrl());

        StepVerifier.create(sender.send(destination, "{\"eventType\":\"TOPIC_APPROVED\"}"))
                .verifyComplete();

        assertThat(await(receivedMethod)).isEqualTo("POST");
        assertThat(await(receivedContentType)).startsWith("application/json");
        assertThat(await(receivedBody)).isEqualTo("{\"eventType\":\"TOPIC_APPROVED\"}");
    }

    @Test
    void addsABearerAuthorizationHeaderOnlyWhenACredentialIsSet() throws IOException {
        CompletableFuture<String> receivedAuth = new CompletableFuture<>();
        server = startServer(exchange -> {
            receivedAuth.complete(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200);
        });

        HttpDestinationSender sender = new HttpDestinationSender();
        EventDestination destination = httpDestination(baseUrl());
        destination.setCredential("secret-token");

        StepVerifier.create(sender.send(destination, "{}")).verifyComplete();

        assertThat(await(receivedAuth)).isEqualTo("Bearer secret-token");
    }

    @Test
    void omitsTheAuthorizationHeaderWhenNoCredentialIsSet() throws IOException {
        CompletableFuture<String> receivedAuth = new CompletableFuture<>();
        server = startServer(exchange -> {
            receivedAuth.complete(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200);
        });

        HttpDestinationSender sender = new HttpDestinationSender();
        EventDestination destination = httpDestination(baseUrl());

        StepVerifier.create(sender.send(destination, "{}")).verifyComplete();

        assertThat(await(receivedAuth)).isNull();
    }

    @Test
    void aPersistentServerFailureIsRetriedThenSwallowedRatherThanPropagated() throws IOException {
        AtomicInteger attempts = new AtomicInteger();
        server = startServer(exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 500);
        });

        HttpDestinationSender sender = new HttpDestinationSender();
        EventDestination destination = httpDestination(baseUrl());

        // research.md §7: 3 retries beyond the initial attempt, ~2s initial backoff — this
        // legitimately takes several seconds to exhaust; a generous StepVerifier timeout covers it.
        StepVerifier.create(sender.send(destination, "{}"))
                .expectComplete()
                .verify(java.time.Duration.ofSeconds(20));

        assertThat(attempts.get()).isEqualTo(4);
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static EventDestination httpDestination(String url) {
        EventDestination destination = new EventDestination();
        destination.setName("Test HTTP Destination");
        destination.setType(EventDestinationType.HTTP_POST);
        destination.setHttpUrl(url);
        return destination;
    }

    private static HttpServer startServer(java.util.function.Consumer<HttpExchange> handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.accept(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static String readBody(HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private static void respond(HttpExchange exchange, int status) {
        try {
            exchange.sendResponseHeaders(status, -1);
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}

package net.fabcelhaft.hackathonorganiser.event;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Delivers an Event's JSON envelope to an {@code HTTP_POST}-type {@link EventDestination}
 * (contracts/delivery-transport.md "HTTP POST Destination"). Uses the constitution-mandated
 * {@link WebClient} (Principle II) — no new dependency.
 */
@Component
public class HttpDestinationSender {

    private static final Logger log = LoggerFactory.getLogger(HttpDestinationSender.class);

    private final WebClient webClient;

    /**
     * Builds its own {@link WebClient} rather than taking an injected {@code WebClient.Builder} —
     * this application has no other HTTP-client bean to share and no {@code WebClientAutoConfiguration}
     * bean is otherwise registered in this project, so depending on one would be a fragile,
     * unnecessary coupling for this feature's single use.
     */
    public HttpDestinationSender() {
        this.webClient = WebClient.builder().build();
    }

    /**
     * Sends {@code jsonBody} to {@code destination.getHttpUrl()}, retrying on any non-2xx response
     * or transport failure per research.md §7. The returned {@code Mono} always completes
     * successfully — a failure that survives retries is logged (FR-020b) and swallowed, never
     * propagated to the caller.
     */
    public Mono<Void> send(EventDestination destination, String jsonBody) {
        WebClient.RequestBodySpec request = webClient
                .post()
                .uri(destination.getHttpUrl())
                .contentType(MediaType.APPLICATION_JSON);
        boolean hasCredential = StringUtils.hasText(destination.getCredential());
        return (hasCredential
                        ? request.header("Authorization", "Bearer " + destination.getCredential())
                        : request)
                .bodyValue(jsonBody)
                .retrieve()
                .toBodilessEntity()
                .then()
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2)))
                .onErrorResume(ex -> {
                    log.warn(
                            "Event delivery to HTTP Destination '{}' ({}) failed after retries: {}",
                            destination.getName(),
                            destination.getHttpUrl(),
                            ex.toString());
                    return Mono.empty();
                });
    }
}

package net.fabcelhaft.hackathonorganiser.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Publishes a {@link DomainEvent} to every currently enabled Destination subscribed to its {@link
 * EventType} (spec.md FR-010-FR-012; research.md §1).
 *
 * <p>{@link #publish(DomainEvent)} returns {@code void} and never blocks: it looks up subscribed,
 * enabled Destinations and, for each, calls {@code .subscribe(...)} on that Destination's
 * transport-specific delivery {@code Mono} — a detached Reactor pipeline the caller never awaits.
 * This is the mechanism FR-020a-1/SC-008 require: the triggering domain occurrence's own reactive
 * chain is never chained to this call, so it is unaffected by how long delivery takes or whether
 * any Destination is reachable.
 *
 * <p>{@link #publish(Mono)} is the same contract for a Participant-related Event, whose payload
 * {@code EventPayloadFactory} can only build asynchronously (FR-010c, FR-010d; research.md §10 of
 * feature 007): it subscribes to the given enrichment {@code Mono} and, once it resolves, dispatches
 * through the exact same pipeline — still detached, so a slow User/Custom-Field lookup delays only
 * this background pipeline, never the caller.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final EventDestinationService eventDestinationService;
    private final HttpDestinationSender httpDestinationSender;
    private final KafkaDestinationSender kafkaDestinationSender;
    private final ObjectMapper objectMapper;

    /**
     * Builds its own {@link ObjectMapper} (with {@link JavaTimeModule} registered, so an {@code
     * Instant} field serializes as an ISO-8601 string per contracts/event-payloads.md's worked
     * examples) rather than taking an injected one — this application's minimal Spring context does
     * not auto-configure an {@code ObjectMapper} bean of its own (research.md §9), and this
     * feature's serialization needs are self-contained.
     */
    public EventPublisher(
            EventDestinationService eventDestinationService,
            HttpDestinationSender httpDestinationSender,
            KafkaDestinationSender kafkaDestinationSender) {
        this.eventDestinationService = eventDestinationService;
        this.httpDestinationSender = httpDestinationSender;
        this.kafkaDestinationSender = kafkaDestinationSender;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void publish(DomainEvent event) {
        String jsonBody = serialize(event);
        if (jsonBody == null) {
            return;
        }
        eventDestinationService
                .findEnabledDestinationsFor(event.eventType())
                .subscribe(
                        destination -> dispatch(destination, jsonBody),
                        ex -> log.warn(
                                "Failed to look up Event Destinations for {}: {}", event.eventType(), ex.toString()));
    }

    public void publish(Mono<DomainEvent> eventMono) {
        eventMono.subscribe(this::publish, ex -> log.warn("Failed to build Event payload: {}", ex.toString()));
    }

    private void dispatch(EventDestination destination, String jsonBody) {
        var delivery = destination.getType() == EventDestinationType.KAFKA
                ? kafkaDestinationSender.send(destination, jsonBody)
                : httpDestinationSender.send(destination, jsonBody);
        delivery.subscribe(
                v -> {},
                ex -> log.warn("Unexpected error delivering to Destination '{}': {}", destination.getName(), ex.toString()));
    }

    private String serialize(DomainEvent event) {
        try {
            var envelope = new java.util.LinkedHashMap<String, Object>();
            envelope.put("eventType", event.eventType().name());
            envelope.putAll(event.payload());
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize Event {}: {}", event.eventType(), ex.toString());
            return null;
        }
    }
}

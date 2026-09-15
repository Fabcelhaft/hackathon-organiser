package net.fabcelhaft.hackathonorganiser.organiser.eventdestination;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.event.EventDestination;
import net.fabcelhaft.hackathonorganiser.event.EventDestinationConflictException;
import net.fabcelhaft.hackathonorganiser.event.EventDestinationService;
import net.fabcelhaft.hackathonorganiser.event.EventDestinationType;
import net.fabcelhaft.hackathonorganiser.event.EventType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.reactive.result.view.Rendering;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Organiser-only views for Event Destinations (spec.md Stories 1, 2 and 4; FR-001-FR-020c).
 * Access to every route here is restricted to {@code ROLE_ORGANISER} by {@code SecurityConfig}'s
 * {@code /organiser/**} path rule (FR-017), matching {@code CustomFieldController}'s
 * {@code Rendering} + {@code ServerWebExchange.getFormData()} pattern.
 */
@Controller
@RequestMapping("/organiser/event-destinations")
public class EventDestinationController {

    private final EventDestinationService eventDestinationService;

    public EventDestinationController(EventDestinationService eventDestinationService) {
        this.eventDestinationService = eventDestinationService;
    }

    @GetMapping
    public Mono<Rendering> list(@RequestParam(name = "flash", required = false) String flash) {
        return eventDestinationService
                .findAll()
                .concatMap(destination -> eventDestinationService
                        .findEventTypes(destination.getId())
                        .map(eventTypes -> new DestinationRow(destination, eventTypes)))
                .collectList()
                .map(rows -> {
                    Rendering.Builder<?> builder = Rendering.view("organiser/event-destinations/list")
                            .modelAttribute("destinations", rows);
                    if (flash != null) {
                        builder = builder.modelAttribute("flash", flash);
                    }
                    return builder.build();
                });
    }

    @GetMapping("/new")
    public Mono<Rendering> newForm() {
        return Mono.just(createFormView(null, null, null, null, null, List.of(), null));
    }

    @PostMapping
    public Mono<Rendering> create(ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> {
            String name = form.getFirst("name");
            EventDestinationType type = typeValue(form.getFirst("type"));
            String kafkaBootstrapServers = form.getFirst("kafka_bootstrap_servers");
            String kafkaTopic = form.getFirst("kafka_topic");
            String httpUrl = form.getFirst("http_url");
            String credential = form.getFirst("credential");
            List<EventType> eventTypes = eventTypeValues(form);

            return eventDestinationService
                    .create(name, type, kafkaBootstrapServers, kafkaTopic, httpUrl, credential, eventTypes)
                    .<Rendering>map(destination -> redirectToList("Event Destination created."))
                    .onErrorResume(
                            EventDestinationConflictException.class,
                            ex -> Mono.just(createFormView(
                                    name, type, kafkaBootstrapServers, kafkaTopic, httpUrl, eventTypes, ex.getMessage())));
        });
    }

    @GetMapping("/{id}/edit")
    public Mono<Rendering> editForm(@PathVariable UUID id) {
        return eventDestinationService
                .findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(destination -> eventDestinationService
                        .findEventTypes(id)
                        .map(eventTypes -> editFormView(destination, eventTypes, null)));
    }

    @PostMapping("/{id}")
    public Mono<Rendering> update(@PathVariable UUID id, ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> {
            String name = form.getFirst("name");
            EventDestinationType type = typeValue(form.getFirst("type"));
            String kafkaBootstrapServers = form.getFirst("kafka_bootstrap_servers");
            String kafkaTopic = form.getFirst("kafka_topic");
            String httpUrl = form.getFirst("http_url");
            String credential = form.getFirst("credential");
            List<EventType> eventTypes = eventTypeValues(form);
            Instant expectedUpdatedAt = Instant.parse(form.getFirst("updated_at"));

            return eventDestinationService
                    .update(
                            id, expectedUpdatedAt, name, type, kafkaBootstrapServers, kafkaTopic, httpUrl, credential,
                            eventTypes)
                    .<Rendering>map(destination -> redirectToList("Event Destination updated."))
                    .onErrorResume(EventDestinationConflictException.class, ex -> {
                        EventDestination resubmitted = new EventDestination();
                        resubmitted.setId(id);
                        resubmitted.setName(name);
                        resubmitted.setType(type);
                        resubmitted.setKafkaBootstrapServers(kafkaBootstrapServers);
                        resubmitted.setKafkaTopic(kafkaTopic);
                        resubmitted.setHttpUrl(httpUrl);
                        resubmitted.setUpdatedAt(expectedUpdatedAt);
                        return Mono.just(editFormView(resubmitted, eventTypes, ex.getMessage()));
                    });
        });
    }

    @PostMapping("/{id}/enable")
    public Mono<Rendering> enable(@PathVariable UUID id) {
        return eventDestinationService
                .enable(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .map(destination -> redirectToList("Event Destination enabled."));
    }

    @PostMapping("/{id}/disable")
    public Mono<Rendering> disable(@PathVariable UUID id) {
        return eventDestinationService
                .disable(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .map(destination -> redirectToList("Event Destination disabled."));
    }

    @PostMapping("/{id}/delete")
    public Mono<Rendering> delete(@PathVariable UUID id) {
        return eventDestinationService.delete(id).then(Mono.just(redirectToList("Event Destination deleted.")));
    }

    private Rendering createFormView(
            String name,
            EventDestinationType type,
            String kafkaBootstrapServers,
            String kafkaTopic,
            String httpUrl,
            List<EventType> selectedEventTypes,
            String error) {
        Rendering.Builder<?> builder = Rendering.view("organiser/event-destinations/form")
                .modelAttribute("eventTypes", EventType.values())
                .modelAttribute("selectedEventTypes", selectedEventTypes)
                .modelAttribute("name", name)
                .modelAttribute("type", type)
                .modelAttribute("kafkaBootstrapServers", kafkaBootstrapServers)
                .modelAttribute("kafkaTopic", kafkaTopic)
                .modelAttribute("httpUrl", httpUrl)
                .modelAttribute("isEdit", false);
        if (error != null) {
            builder = builder.modelAttribute("error", error);
        }
        return builder.build();
    }

    private Rendering editFormView(EventDestination destination, List<EventType> selectedEventTypes, String error) {
        Rendering.Builder<?> builder = Rendering.view("organiser/event-destinations/form")
                .modelAttribute("eventTypes", EventType.values())
                .modelAttribute("selectedEventTypes", selectedEventTypes)
                .modelAttribute("destinationId", destination.getId())
                .modelAttribute("name", destination.getName())
                .modelAttribute("type", destination.getType())
                .modelAttribute("kafkaBootstrapServers", destination.getKafkaBootstrapServers())
                .modelAttribute("kafkaTopic", destination.getKafkaTopic())
                .modelAttribute("httpUrl", destination.getHttpUrl())
                .modelAttribute("updatedAt", destination.getUpdatedAt())
                .modelAttribute("isEdit", true);
        if (error != null) {
            builder = builder.modelAttribute("error", error);
        }
        return builder.build();
    }

    private static Rendering redirectToList(String flash) {
        return Rendering.redirectTo("/organiser/event-destinations?flash=" + java.net.URLEncoder.encode(
                        flash, java.nio.charset.StandardCharsets.UTF_8))
                .status(HttpStatus.SEE_OTHER)
                .build();
    }

    private static EventDestinationType typeValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EventDestinationType.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static List<EventType> eventTypeValues(MultiValueMap<String, String> form) {
        List<String> raw = form.get("event_types");
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(EventType::valueOf)
                .toList();
    }

    /** The list view's row shape: a Destination plus its currently subscribed Event Types. */
    public record DestinationRow(EventDestination destination, List<EventType> eventTypes) {}
}

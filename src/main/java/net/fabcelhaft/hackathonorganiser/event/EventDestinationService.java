package net.fabcelhaft.hackathonorganiser.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Create, read, update, enable/disable, and delete {@link EventDestination}s, plus their {@link
 * EventType} subscriptions (data-model.md "Event Destination", "Event Destination x Event Type";
 * FR-001-FR-020c).
 */
@Service
public class EventDestinationService {

    private final EventDestinationRepository eventDestinationRepository;
    private final DatabaseClient databaseClient;
    private final KafkaDestinationSender kafkaDestinationSender;

    public EventDestinationService(
            EventDestinationRepository eventDestinationRepository,
            DatabaseClient databaseClient,
            KafkaDestinationSender kafkaDestinationSender) {
        this.eventDestinationRepository = eventDestinationRepository;
        this.databaseClient = databaseClient;
        this.kafkaDestinationSender = kafkaDestinationSender;
    }

    public Flux<EventDestination> findAll() {
        return eventDestinationRepository.findAll();
    }

    public Mono<EventDestination> findById(UUID id) {
        return eventDestinationRepository.findById(id);
    }

    /** Creates a new, always-disabled (FR-006) Event Destination. */
    public Mono<EventDestination> create(
            String name,
            EventDestinationType type,
            String kafkaBootstrapServers,
            String kafkaTopic,
            String httpUrl,
            String credential,
            List<EventType> eventTypes) {
        return validateName(name)
                .then(validateTypeFields(type, kafkaBootstrapServers, kafkaTopic, httpUrl))
                .then(Mono.defer(() -> rejectIfNameTaken(name, null)))
                .then(Mono.defer(() -> {
                    EventDestination destination = new EventDestination();
                    destination.setName(name);
                    destination.setType(type);
                    destination.setEnabled(false);
                    applyTypeFields(destination, type, kafkaBootstrapServers, kafkaTopic, httpUrl);
                    destination.setCredential(StringUtils.hasText(credential) ? credential : null);
                    Instant now = Instant.now();
                    destination.setCreatedAt(now);
                    destination.setUpdatedAt(now);
                    return eventDestinationRepository.save(destination);
                }))
                .flatMap(saved -> replaceEventTypeSelections(saved.getId(), eventTypes).thenReturn(saved));
    }

    /**
     * Updates an existing Destination. Rejects the save with {@link
     * EventDestinationConflictException} if {@code expectedUpdatedAt} no longer matches the
     * current row (FR-018) — a concurrent edit by another Organiser. Changing {@code type}
     * discards the other type's connection fields (FR-020). A blank {@code credential} leaves the
     * previously stored value untouched (FR-019).
     */
    public Mono<EventDestination> update(
            UUID id,
            Instant expectedUpdatedAt,
            String name,
            EventDestinationType type,
            String kafkaBootstrapServers,
            String kafkaTopic,
            String httpUrl,
            String credential,
            List<EventType> eventTypes) {
        return validateName(name)
                .then(validateTypeFields(type, kafkaBootstrapServers, kafkaTopic, httpUrl))
                .then(eventDestinationRepository
                        .findById(id)
                        .switchIfEmpty(Mono.error(new EventDestinationConflictException("Event Destination not found")))
                        .flatMap(existing -> {
                            // Compared at microsecond precision — Postgres' timestamptz column stores at most
                            // microsecond resolution, while the hidden form field round-trips the value this
                            // service itself last wrote via Instant#toString(), which can carry Java's finer
                            // (nanosecond-capable) Instant precision; comparing at full precision would reject
                            // even a same-value resubmission as a false stale-write conflict.
                            if (!existing.getUpdatedAt()
                                    .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                                    .equals(expectedUpdatedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS))) {
                                return Mono.error(new EventDestinationConflictException(
                                        "This Event Destination was changed by someone else — reload and try again"));
                            }
                            return rejectIfNameTaken(name, id).thenReturn(existing);
                        }))
                .flatMap(existing -> {
                    existing.setName(name);
                    existing.setType(type);
                    clearTypeFields(existing);
                    applyTypeFields(existing, type, kafkaBootstrapServers, kafkaTopic, httpUrl);
                    if (StringUtils.hasText(credential)) {
                        existing.setCredential(credential);
                    }
                    existing.setUpdatedAt(Instant.now());
                    return eventDestinationRepository.save(existing);
                })
                .flatMap(saved -> replaceEventTypeSelections(saved.getId(), eventTypes).thenReturn(saved));
    }

    public Mono<EventDestination> enable(UUID id) {
        return setEnabled(id, true);
    }

    public Mono<EventDestination> disable(UUID id) {
        return setEnabled(id, false);
    }

    private Mono<EventDestination> setEnabled(UUID id, boolean enabled) {
        return eventDestinationRepository
                .findById(id)
                .switchIfEmpty(Mono.error(new EventDestinationConflictException("Event Destination not found")))
                .flatMap(existing -> {
                    existing.setEnabled(enabled);
                    existing.setUpdatedAt(Instant.now());
                    return eventDestinationRepository.save(existing);
                });
    }

    public Mono<Void> delete(UUID id) {
        return eventDestinationRepository
                .findById(id)
                .flatMap(existing -> {
                    if (existing.getType() == EventDestinationType.KAFKA) {
                        kafkaDestinationSender.disposeCacheFor(id);
                    }
                    return deleteEventTypeSelections(id)
                            .then(eventDestinationRepository.deleteById(id));
                });
    }

    /** The Event Types currently selected for a Destination (FR-008, FR-009). */
    public Mono<List<EventType>> findEventTypes(UUID destinationId) {
        return databaseClient
                .sql("SELECT event_type FROM event_destination_event_types WHERE event_destination_id = :did")
                .bind("did", destinationId)
                .mapValue(String.class)
                .all()
                .map(EventType::valueOf)
                .collectList();
    }

    /**
     * Every currently enabled Destination subscribed to {@code eventType} (FR-011) — the query
     * {@link EventPublisher} calls for every published Event.
     */
    public Flux<EventDestination> findEnabledDestinationsFor(EventType eventType) {
        return databaseClient
                .sql(
                        "SELECT ed.id, ed.name, ed.type, ed.enabled, ed.kafka_bootstrap_servers, ed.kafka_topic, "
                                + "ed.http_url, ed.credential, ed.created_at, ed.updated_at FROM event_destinations ed "
                                + "JOIN event_destination_event_types edet ON edet.event_destination_id = ed.id "
                                + "WHERE ed.enabled = true AND edet.event_type = :eventType")
                .bind("eventType", eventType.name())
                .map((row, metadata) -> {
                    EventDestination destination = new EventDestination();
                    destination.setId(row.get("id", UUID.class));
                    destination.setName(row.get("name", String.class));
                    destination.setType(EventDestinationType.valueOf(row.get("type", String.class)));
                    destination.setEnabled(Boolean.TRUE.equals(row.get("enabled", Boolean.class)));
                    destination.setKafkaBootstrapServers(row.get("kafka_bootstrap_servers", String.class));
                    destination.setKafkaTopic(row.get("kafka_topic", String.class));
                    destination.setHttpUrl(row.get("http_url", String.class));
                    destination.setCredential(row.get("credential", String.class));
                    destination.setCreatedAt(row.get("created_at", java.time.Instant.class));
                    destination.setUpdatedAt(row.get("updated_at", java.time.Instant.class));
                    return destination;
                })
                .all();
    }

    private Mono<Void> replaceEventTypeSelections(UUID destinationId, List<EventType> eventTypes) {
        List<EventType> distinct = eventTypes == null ? List.of() : eventTypes.stream().distinct().toList();
        return deleteEventTypeSelections(destinationId)
                .thenMany(Flux.fromIterable(distinct))
                .concatMap(eventType -> databaseClient
                        .sql("INSERT INTO event_destination_event_types (event_destination_id, event_type) "
                                + "VALUES (:did, :et)")
                        .bind("did", destinationId)
                        .bind("et", eventType.name())
                        .then())
                .then();
    }

    private Mono<Void> deleteEventTypeSelections(UUID destinationId) {
        return databaseClient
                .sql("DELETE FROM event_destination_event_types WHERE event_destination_id = :did")
                .bind("did", destinationId)
                .then();
    }

    private Mono<Void> rejectIfNameTaken(String name, UUID excludingId) {
        return eventDestinationRepository
                .findByName(name)
                .filter(existing -> excludingId == null || !existing.getId().equals(excludingId))
                .flatMap(existing -> Mono.<Void>error(
                        new EventDestinationConflictException("An Event Destination named '" + name + "' already exists")))
                .then();
    }

    private Mono<Void> validateName(String name) {
        if (!StringUtils.hasText(name)) {
            return Mono.error(new EventDestinationConflictException("A name is required"));
        }
        return Mono.empty();
    }

    private Mono<Void> validateTypeFields(
            EventDestinationType type, String kafkaBootstrapServers, String kafkaTopic, String httpUrl) {
        if (type == null) {
            return Mono.error(new EventDestinationConflictException("A Destination type is required"));
        }
        if (type == EventDestinationType.KAFKA) {
            if (!StringUtils.hasText(kafkaBootstrapServers)) {
                return Mono.error(
                        new EventDestinationConflictException("Kafka bootstrap servers are required for a Kafka Destination"));
            }
            if (!StringUtils.hasText(kafkaTopic)) {
                return Mono.error(new EventDestinationConflictException("A Kafka topic is required for a Kafka Destination"));
            }
        } else if (type == EventDestinationType.HTTP_POST) {
            if (!StringUtils.hasText(httpUrl)) {
                return Mono.error(new EventDestinationConflictException("A URL is required for an HTTP POST Destination"));
            }
        }
        return Mono.empty();
    }

    private void applyTypeFields(
            EventDestination destination,
            EventDestinationType type,
            String kafkaBootstrapServers,
            String kafkaTopic,
            String httpUrl) {
        if (type == EventDestinationType.KAFKA) {
            destination.setKafkaBootstrapServers(kafkaBootstrapServers);
            destination.setKafkaTopic(kafkaTopic);
        } else {
            destination.setHttpUrl(httpUrl);
        }
    }

    private void clearTypeFields(EventDestination destination) {
        destination.setKafkaBootstrapServers(null);
        destination.setKafkaTopic(null);
        destination.setHttpUrl(null);
    }
}

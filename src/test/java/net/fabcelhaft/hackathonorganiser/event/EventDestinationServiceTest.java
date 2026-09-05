package net.fabcelhaft.hackathonorganiser.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link EventDestinationService} (spec.md US1/US2/US4; FR-001-FR-020c). Per
 * Constitution Development Workflow #4, multi-operator reactive chains are verified with
 * {@link StepVerifier}, never {@code .block()}. Mirrors {@code TopicServiceTest}'s {@code
 * DatabaseClient}-mocking style (a generic {@code stubWriteAlwaysSucceeds()} covering every
 * association-table write against {@code event_destination_event_types}, mirroring {@code
 * topic_skills}).
 */
@ExtendWith(MockitoExtension.class)
class EventDestinationServiceTest {

    @Mock
    private EventDestinationRepository eventDestinationRepository;

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private DatabaseClient.GenericExecuteSpec executeSpec;

    @Mock
    private KafkaDestinationSender kafkaDestinationSender;

    private EventDestinationService service;

    @BeforeEach
    void setUp() {
        service = new EventDestinationService(eventDestinationRepository, databaseClient, kafkaDestinationSender);
        lenient().when(eventDestinationRepository.save(any(EventDestination.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    private void stubWriteAlwaysSucceeds() {
        lenient().when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        lenient().when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        lenient().when(executeSpec.then()).thenReturn(Mono.empty());
    }

    // --- create: per-type required fields (FR-002, FR-003, FR-004) ------------------------------

    @Test
    void createRejectsMissingName() {
        StepVerifier.create(service.create(
                        null, EventDestinationType.HTTP_POST, null, null, "https://example.com", null, List.of()))
                .expectError(EventDestinationConflictException.class)
                .verify();

        verify(eventDestinationRepository, never()).save(any());
    }

    @Test
    void createKafkaRejectsMissingBootstrapServers() {
        StepVerifier.create(service.create(
                        "Kafka Dest", EventDestinationType.KAFKA, null, "topic", null, null, List.of()))
                .expectError(EventDestinationConflictException.class)
                .verify();

        verify(eventDestinationRepository, never()).save(any());
    }

    @Test
    void createKafkaRejectsMissingTopic() {
        StepVerifier.create(service.create(
                        "Kafka Dest", EventDestinationType.KAFKA, "localhost:9092", null, null, null, List.of()))
                .expectError(EventDestinationConflictException.class)
                .verify();

        verify(eventDestinationRepository, never()).save(any());
    }

    @Test
    void createHttpRejectsMissingUrl() {
        StepVerifier.create(service.create(
                        "HTTP Dest", EventDestinationType.HTTP_POST, null, null, null, null, List.of()))
                .expectError(EventDestinationConflictException.class)
                .verify();

        verify(eventDestinationRepository, never()).save(any());
    }

    @Test
    void createKafkaSucceedsWithBothRequiredFields() {
        when(eventDestinationRepository.findByName("Kafka Dest")).thenReturn(Mono.empty());
        stubWriteAlwaysSucceeds();

        StepVerifier.create(service.create(
                        "Kafka Dest", EventDestinationType.KAFKA, "localhost:9092", "events", null, null, List.of()))
                .assertNext(destination -> {
                    assertThat(destination.getKafkaBootstrapServers()).isEqualTo("localhost:9092");
                    assertThat(destination.getKafkaTopic()).isEqualTo("events");
                    assertThat(destination.isEnabled()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void createHttpSucceedsWithUrl() {
        when(eventDestinationRepository.findByName("HTTP Dest")).thenReturn(Mono.empty());
        stubWriteAlwaysSucceeds();

        StepVerifier.create(service.create(
                        "HTTP Dest",
                        EventDestinationType.HTTP_POST,
                        null,
                        null,
                        "https://example.com/webhook",
                        null,
                        List.of()))
                .assertNext(destination -> {
                    assertThat(destination.getHttpUrl()).isEqualTo("https://example.com/webhook");
                    assertThat(destination.isEnabled()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void createRejectsADuplicateName() {
        EventDestination existing = new EventDestination();
        existing.setId(UUID.randomUUID());
        when(eventDestinationRepository.findByName("Dup")).thenReturn(Mono.just(existing));

        StepVerifier.create(service.create(
                        "Dup", EventDestinationType.HTTP_POST, null, null, "https://example.com", null, List.of()))
                .expectError(EventDestinationConflictException.class)
                .verify();

        verify(eventDestinationRepository, never()).save(any());
    }

    // --- create: Event Type selections (FR-008, FR-009) ------------------------------------------

    @Test
    void createWithNoEventTypesSelectedSucceeds() {
        when(eventDestinationRepository.findByName("Dest")).thenReturn(Mono.empty());
        stubWriteAlwaysSucceeds();

        StepVerifier.create(service.create(
                        "Dest", EventDestinationType.HTTP_POST, null, null, "https://example.com", null, List.of()))
                .expectNextCount(1)
                .verifyComplete();

        verify(databaseClient).sql(org.mockito.ArgumentMatchers.contains("DELETE FROM event_destination_event_types"));
        verify(databaseClient, never())
                .sql(org.mockito.ArgumentMatchers.contains("INSERT INTO event_destination_event_types"));
    }

    @Test
    void createPersistsTheSelectedEventTypes() {
        when(eventDestinationRepository.findByName("Dest")).thenReturn(Mono.empty());
        stubWriteAlwaysSucceeds();

        StepVerifier.create(service.create(
                        "Dest",
                        EventDestinationType.HTTP_POST,
                        null,
                        null,
                        "https://example.com",
                        null,
                        List.of(EventType.PARTICIPANT_REGISTERED, EventType.TOPIC_APPROVED)))
                .expectNextCount(1)
                .verifyComplete();

        verify(databaseClient, org.mockito.Mockito.times(2))
                .sql(org.mockito.ArgumentMatchers.contains("INSERT INTO event_destination_event_types"));
    }

    // --- update: stale-write conflict (FR-018) -----------------------------------------------------

    @Test
    void updateWithTheCurrentUpdatedAtApplies() {
        Instant currentUpdatedAt = Instant.parse("2026-01-01T00:00:00Z");
        UUID id = UUID.randomUUID();
        EventDestination existing = destinationOf(id, "Old Name", currentUpdatedAt);
        when(eventDestinationRepository.findById(id)).thenReturn(Mono.just(existing));
        when(eventDestinationRepository.findByName("New Name")).thenReturn(Mono.empty());
        stubWriteAlwaysSucceeds();

        StepVerifier.create(service.update(
                        id,
                        currentUpdatedAt,
                        "New Name",
                        EventDestinationType.HTTP_POST,
                        null,
                        null,
                        "https://example.com",
                        null,
                        List.of()))
                .assertNext(destination -> assertThat(destination.getName()).isEqualTo("New Name"))
                .verifyComplete();
    }

    @Test
    void updateWithAStaleUpdatedAtIsRejected() {
        Instant currentUpdatedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant staleUpdatedAt = Instant.parse("2025-01-01T00:00:00Z");
        UUID id = UUID.randomUUID();
        EventDestination existing = destinationOf(id, "Old Name", currentUpdatedAt);
        when(eventDestinationRepository.findById(id)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.update(
                        id,
                        staleUpdatedAt,
                        "New Name",
                        EventDestinationType.HTTP_POST,
                        null,
                        null,
                        "https://example.com",
                        null,
                        List.of()))
                .expectError(EventDestinationConflictException.class)
                .verify();

        verify(eventDestinationRepository, never()).save(any());
    }

    @Test
    void updateChangingTypeDiscardsThePreviousTypesFields() {
        Instant currentUpdatedAt = Instant.parse("2026-01-01T00:00:00Z");
        UUID id = UUID.randomUUID();
        EventDestination existing = destinationOf(id, "Dest", currentUpdatedAt);
        existing.setType(EventDestinationType.KAFKA);
        existing.setKafkaBootstrapServers("localhost:9092");
        existing.setKafkaTopic("events");
        when(eventDestinationRepository.findById(id)).thenReturn(Mono.just(existing));
        when(eventDestinationRepository.findByName("Dest")).thenReturn(Mono.just(existing));
        stubWriteAlwaysSucceeds();

        StepVerifier.create(service.update(
                        id,
                        currentUpdatedAt,
                        "Dest",
                        EventDestinationType.HTTP_POST,
                        null,
                        null,
                        "https://example.com",
                        null,
                        List.of()))
                .assertNext(destination -> {
                    assertThat(destination.getKafkaBootstrapServers()).isNull();
                    assertThat(destination.getKafkaTopic()).isNull();
                    assertThat(destination.getHttpUrl()).isEqualTo("https://example.com");
                })
                .verifyComplete();
    }

    @Test
    void updateWithABlankCredentialLeavesThePreviousValueUntouched() {
        Instant currentUpdatedAt = Instant.parse("2026-01-01T00:00:00Z");
        UUID id = UUID.randomUUID();
        EventDestination existing = destinationOf(id, "Dest", currentUpdatedAt);
        existing.setType(EventDestinationType.HTTP_POST);
        existing.setHttpUrl("https://example.com");
        existing.setCredential("secret-token");
        when(eventDestinationRepository.findById(id)).thenReturn(Mono.just(existing));
        when(eventDestinationRepository.findByName("Dest")).thenReturn(Mono.just(existing));
        stubWriteAlwaysSucceeds();

        StepVerifier.create(service.update(
                        id,
                        currentUpdatedAt,
                        "Dest",
                        EventDestinationType.HTTP_POST,
                        null,
                        null,
                        "https://example.com",
                        "",
                        List.of()))
                .assertNext(destination -> assertThat(destination.getCredential()).isEqualTo("secret-token"))
                .verifyComplete();
    }

    @Test
    void updateWithANonBlankCredentialOverwritesTheStoredValue() {
        Instant currentUpdatedAt = Instant.parse("2026-01-01T00:00:00Z");
        UUID id = UUID.randomUUID();
        EventDestination existing = destinationOf(id, "Dest", currentUpdatedAt);
        existing.setType(EventDestinationType.HTTP_POST);
        existing.setHttpUrl("https://example.com");
        existing.setCredential("old-secret");
        when(eventDestinationRepository.findById(id)).thenReturn(Mono.just(existing));
        when(eventDestinationRepository.findByName("Dest")).thenReturn(Mono.just(existing));
        stubWriteAlwaysSucceeds();

        StepVerifier.create(service.update(
                        id,
                        currentUpdatedAt,
                        "Dest",
                        EventDestinationType.HTTP_POST,
                        null,
                        null,
                        "https://example.com",
                        "new-secret",
                        List.of()))
                .assertNext(destination -> assertThat(destination.getCredential()).isEqualTo("new-secret"))
                .verifyComplete();
    }

    // --- enable/disable (FR-013) --------------------------------------------------------------------

    @Test
    void enableChangesOnlyTheEnabledFlag() {
        UUID id = UUID.randomUUID();
        EventDestination existing = destinationOf(id, "Dest", Instant.now());
        existing.setEnabled(false);
        existing.setHttpUrl("https://example.com");
        when(eventDestinationRepository.findById(id)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.enable(id))
                .assertNext(destination -> {
                    assertThat(destination.isEnabled()).isTrue();
                    assertThat(destination.getHttpUrl()).isEqualTo("https://example.com");
                })
                .verifyComplete();
    }

    @Test
    void disableChangesOnlyTheEnabledFlag() {
        UUID id = UUID.randomUUID();
        EventDestination existing = destinationOf(id, "Dest", Instant.now());
        existing.setEnabled(true);
        when(eventDestinationRepository.findById(id)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.disable(id))
                .assertNext(destination -> assertThat(destination.isEnabled()).isFalse())
                .verifyComplete();
    }

    // --- delete (FR-015) -----------------------------------------------------------------------------

    @Test
    void deleteOfAKafkaDestinationDisposesItsCachedProducer() {
        UUID id = UUID.randomUUID();
        EventDestination existing = destinationOf(id, "Dest", Instant.now());
        existing.setType(EventDestinationType.KAFKA);
        when(eventDestinationRepository.findById(id)).thenReturn(Mono.just(existing));
        when(eventDestinationRepository.deleteById(id)).thenReturn(Mono.empty());
        stubWriteAlwaysSucceeds();

        StepVerifier.create(service.delete(id)).verifyComplete();

        verify(kafkaDestinationSender).disposeCacheFor(id);
        verify(eventDestinationRepository).deleteById(id);
    }

    @Test
    void deleteOfAnHttpDestinationDoesNotTouchTheKafkaSenderCache() {
        UUID id = UUID.randomUUID();
        EventDestination existing = destinationOf(id, "Dest", Instant.now());
        existing.setType(EventDestinationType.HTTP_POST);
        when(eventDestinationRepository.findById(id)).thenReturn(Mono.just(existing));
        when(eventDestinationRepository.deleteById(id)).thenReturn(Mono.empty());
        stubWriteAlwaysSucceeds();

        StepVerifier.create(service.delete(id)).verifyComplete();

        verify(kafkaDestinationSender, never()).disposeCacheFor(any());
    }

    private static EventDestination destinationOf(UUID id, String name, Instant updatedAt) {
        EventDestination destination = new EventDestination();
        destination.setId(id);
        destination.setName(name);
        destination.setCreatedAt(updatedAt);
        destination.setUpdatedAt(updatedAt);
        return destination;
    }
}

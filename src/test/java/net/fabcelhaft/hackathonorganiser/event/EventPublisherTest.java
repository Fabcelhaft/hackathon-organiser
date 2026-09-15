package net.fabcelhaft.hackathonorganiser.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Unit tests for {@link EventPublisher} (spec.md FR-010-FR-012, FR-020a-1; research.md §1).
 * {@code publish(...)} must reach every enabled, subscribed Destination independently (FR-012),
 * skip disabled/unsubscribed ones (FR-011), and — critically — never make the caller wait for
 * delivery (FR-020a-1/SC-008), verified here by proving {@code publish(...)} returns before a
 * manually-controlled delivery {@code Mono} completes.
 */
@ExtendWith(MockitoExtension.class)
class EventPublisherTest {

    @Mock
    private EventDestinationService eventDestinationService;

    @Mock
    private HttpDestinationSender httpDestinationSender;

    @Mock
    private KafkaDestinationSender kafkaDestinationSender;

    private EventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new EventPublisher(eventDestinationService, httpDestinationSender, kafkaDestinationSender);
    }

    @Test
    void publishSendsToEveryEnabledSubscribedDestinationIndependently() {
        EventDestination http1 = httpDestination("http1");
        EventDestination http2 = httpDestination("http2");
        when(eventDestinationService.findEnabledDestinationsFor(EventType.PARTICIPANT_REGISTERED))
                .thenReturn(Flux.just(http1, http2));
        when(httpDestinationSender.send(eq(http1), any())).thenReturn(Mono.empty());
        when(httpDestinationSender.send(eq(http2), any())).thenReturn(Mono.error(new RuntimeException("boom")));

        publisher.publish(new DomainEvent(EventType.PARTICIPANT_REGISTERED, Map.of("participant", Map.of("id", "x"))));

        // Both sends were dispatched — http2's failure does not prevent or affect http1's own call.
        verify(httpDestinationSender, times(1)).send(eq(http1), any());
        verify(httpDestinationSender, times(1)).send(eq(http2), any());
    }

    @Test
    void publishSkipsADisabledOrUnsubscribedDestinationByConstruction() {
        // findEnabledDestinationsFor already excludes disabled/unsubscribed Destinations (FR-011);
        // an empty result means no sender is ever invoked.
        when(eventDestinationService.findEnabledDestinationsFor(EventType.TOPIC_APPROVED)).thenReturn(Flux.empty());

        publisher.publish(new DomainEvent(EventType.TOPIC_APPROVED, Map.of("topic", Map.of("id", "x"))));

        verify(httpDestinationSender, never()).send(any(), any());
        verify(kafkaDestinationSender, never()).send(any(), any());
    }

    @Test
    void publishDispatchesToTheSenderMatchingEachDestinationsType() {
        EventDestination kafka = kafkaDestination("kafka1");
        when(eventDestinationService.findEnabledDestinationsFor(EventType.GROUP_FORMED)).thenReturn(Flux.just(kafka));
        when(kafkaDestinationSender.send(eq(kafka), any())).thenReturn(Mono.empty());

        publisher.publish(new DomainEvent(EventType.GROUP_FORMED, Map.of("group", Map.of("id", "x"))));

        verify(kafkaDestinationSender).send(eq(kafka), any());
        verify(httpDestinationSender, never()).send(any(), any());
    }

    @Test
    @Timeout(5)
    void publishReturnsBeforeDeliveryCompletes() {
        EventDestination http1 = httpDestination("http1");
        when(eventDestinationService.findEnabledDestinationsFor(EventType.PARTICIPANT_REGISTERED))
                .thenReturn(Flux.just(http1));
        // A Sinks.One that is deliberately never completed within this test: if publish(...) ever
        // blocked (e.g. called .block() internally) waiting for this delivery Mono, the test
        // itself would hang and fail its @Timeout — the only way it can pass is if publish(...)
        // merely subscribes to the delivery Mono and returns immediately (FR-020a-1/SC-008),
        // without waiting for it to emit.
        Sinks.One<Void> deliverySink = Sinks.one();
        when(httpDestinationSender.send(eq(http1), any())).thenReturn(deliverySink.asMono());

        long start = System.nanoTime();
        publisher.publish(new DomainEvent(EventType.PARTICIPANT_REGISTERED, Map.of("participant", Map.of("id", "x"))));
        long elapsed = System.nanoTime() - start;

        org.assertj.core.api.Assertions.assertThat(Duration.ofNanos(elapsed)).isLessThan(Duration.ofSeconds(1));
        verify(httpDestinationSender).send(eq(http1), any());
    }

    @Test
    void publishOfAMonoResolvesThenDispatchesThroughTheSamePipeline() {
        EventDestination http1 = httpDestination("http1");
        when(eventDestinationService.findEnabledDestinationsFor(EventType.PARTICIPANT_REGISTERED))
                .thenReturn(Flux.just(http1));
        when(httpDestinationSender.send(eq(http1), any())).thenReturn(Mono.empty());

        publisher.publish(Mono.just(
                new DomainEvent(EventType.PARTICIPANT_REGISTERED, Map.of("participant", Map.of("id", "x")))));

        verify(httpDestinationSender).send(eq(http1), any());
    }

    @Test
    @Timeout(5)
    void publishOfAMonoReturnsBeforeTheEnrichmentMonoResolves() {
        // Proves a slow User/Custom-Field lookup (research.md §10) cannot delay the caller: this
        // Mono is deliberately never completed within this test, so publish(Mono<DomainEvent>)
        // must merely subscribe to it and return, never .block() waiting for it (FR-020a-1/SC-008).
        Sinks.One<DomainEvent> eventSink = Sinks.one();

        long start = System.nanoTime();
        publisher.publish(eventSink.asMono());
        long elapsed = System.nanoTime() - start;

        org.assertj.core.api.Assertions.assertThat(Duration.ofNanos(elapsed)).isLessThan(Duration.ofSeconds(1));
    }

    private static EventDestination httpDestination(String name) {
        EventDestination destination = new EventDestination();
        destination.setId(UUID.randomUUID());
        destination.setName(name);
        destination.setType(EventDestinationType.HTTP_POST);
        destination.setHttpUrl("https://example.com/" + name);
        destination.setEnabled(true);
        return destination;
    }

    private static EventDestination kafkaDestination(String name) {
        EventDestination destination = new EventDestination();
        destination.setId(UUID.randomUUID());
        destination.setName(name);
        destination.setType(EventDestinationType.KAFKA);
        destination.setKafkaBootstrapServers("localhost:9092");
        destination.setKafkaTopic("events");
        destination.setEnabled(true);
        return destination;
    }
}

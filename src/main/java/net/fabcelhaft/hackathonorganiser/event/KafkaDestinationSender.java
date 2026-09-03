package net.fabcelhaft.hackathonorganiser.event;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Delivers an Event's JSON envelope to a {@code KAFKA}-type {@link EventDestination}
 * (contracts/delivery-transport.md "Kafka Destination"). Each Destination may point at a
 * different broker, so a single Spring-managed {@code KafkaTemplate} cannot represent this —
 * instead a small {@link KafkaProducer} cache is keyed by Destination id, built lazily from that
 * Destination's own {@code kafkaBootstrapServers} (research.md §2).
 */
@Component
public class KafkaDestinationSender {

    private static final Logger log = LoggerFactory.getLogger(KafkaDestinationSender.class);

    private final Map<UUID, KafkaProducer<String, String>> producers = new ConcurrentHashMap<>();

    /**
     * Sends {@code jsonBody} to {@code destination.getKafkaTopic()} with a {@code null} key,
     * retrying on any producer-reported send exception per research.md §7. The returned {@code
     * Mono} always completes successfully — a failure that survives retries is logged (FR-020b)
     * and swallowed, never propagated to the caller.
     */
    public Mono<Void> send(EventDestination destination, String jsonBody) {
        return Mono.<Void>create(sink -> {
                    KafkaProducer<String, String> producer = producerFor(destination);
                    ProducerRecord<String, String> record =
                            new ProducerRecord<>(destination.getKafkaTopic(), null, jsonBody);
                    producer.send(record, (metadata, exception) -> {
                        if (exception != null) {
                            sink.error(exception);
                        } else {
                            sink.success();
                        }
                    });
                })
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2)))
                .onErrorResume(ex -> {
                    log.warn(
                            "Event delivery to Kafka Destination '{}' (topic '{}') failed after retries: {}",
                            destination.getName(),
                            destination.getKafkaTopic(),
                            ex.toString());
                    return Mono.empty();
                });
    }

    private KafkaProducer<String, String> producerFor(EventDestination destination) {
        return producers.computeIfAbsent(destination.getId(), id -> newProducer(destination));
    }

    private KafkaProducer<String, String> newProducer(EventDestination destination) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, destination.getKafkaBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        if (StringUtils.hasText(destination.getCredential())) {
            properties.put("security.protocol", "SASL_PLAINTEXT");
            properties.put("sasl.mechanism", "PLAIN");
            properties.put(
                    "sasl.jaas.config",
                    "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
                            + destination.getName() + "\" password=\"" + destination.getCredential() + "\";");
        }
        return new KafkaProducer<>(properties);
    }

    /**
     * Closes and evicts the cached producer for a Destination, if one exists — called when a
     * {@code KAFKA} Destination is edited (its broker/topic may have changed) or deleted (US4,
     * FR-015).
     */
    public void disposeCacheFor(UUID destinationId) {
        KafkaProducer<String, String> producer = producers.remove(destinationId);
        if (producer != null) {
            producer.close();
        }
    }
}

package net.fabcelhaft.hackathonorganiser.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import reactor.test.StepVerifier;

/**
 * Component tests for {@link KafkaDestinationSender} (contracts/delivery-transport.md "Kafka
 * Destination"; research.md §2, §8) against a real, single-broker Kafka via Testcontainers.
 */
@Testcontainers
class KafkaDestinationSenderIT {

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    private final KafkaDestinationSender sender = new KafkaDestinationSender();

    @AfterEach
    void tearDown() {
        // Every EventDestination id used below is disposed so no test leaks a live producer.
    }

    @Test
    void producesTheJsonEnvelopeToTheConfiguredTopicWithANullKey() {
        String topic = "events-" + UUID.randomUUID();
        EventDestination destination = kafkaDestination(topic);

        StepVerifier.create(sender.send(destination, "{\"eventType\":\"TOPIC_APPROVED\"}"))
                .verifyComplete();

        ConsumerRecord<String, String> record = consumeOne(topic);
        assertThat(record.key()).isNull();
        assertThat(record.value()).isEqualTo("{\"eventType\":\"TOPIC_APPROVED\"}");

        sender.disposeCacheFor(destination.getId());
    }

    @Test
    void anUnreachableBrokerIsRetriedThenSwallowedRatherThanPropagated() {
        EventDestination destination = new EventDestination();
        destination.setId(UUID.randomUUID());
        destination.setName("Unreachable");
        destination.setType(EventDestinationType.KAFKA);
        destination.setKafkaBootstrapServers("localhost:1");
        destination.setKafkaTopic("events");

        try {
            // research.md §7: bounded retries then a swallowed completion (FR-020b). With
            // max.block.ms=10s (KafkaDestinationSender) and 3 Reactor-level retries backing off
            // 2s/4s/8s, the worst case is ~54s, so the verify budget is set well above that.
            StepVerifier.create(sender.send(destination, "{}"))
                    .expectComplete()
                    .verify(Duration.ofSeconds(90));
        } finally {
            // Always dispose, even on failure — an undisposed producer against this unreachable
            // broker keeps retrying in background threads for the rest of the test JVM's lifetime,
            // starving unrelated tests that share this fork.
            sender.disposeCacheFor(destination.getId());
        }
    }

    private EventDestination kafkaDestination(String topic) {
        EventDestination destination = new EventDestination();
        destination.setId(UUID.randomUUID());
        destination.setName("Test Kafka Destination");
        destination.setType(EventDestinationType.KAFKA);
        destination.setKafkaBootstrapServers(kafka.getBootstrapServers());
        destination.setKafkaTopic(topic);
        return destination;
    }

    private ConsumerRecord<String, String> consumeOne(String topic) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(Collections.singletonList(topic));
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                if (!records.isEmpty()) {
                    return records.iterator().next();
                }
            }
            throw new AssertionError("No record consumed from topic " + topic + " within the deadline");
        }
    }
}

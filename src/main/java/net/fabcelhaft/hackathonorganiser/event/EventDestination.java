package net.fabcelhaft.hackathonorganiser.event;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * An Organiser-configured target for outbound Events (spec.md Key Entities: Event Destination;
 * data-model.md "Event Destination" — FR-001-FR-020c).
 *
 * <p>{@code id} is left {@code null} on construction: PostgreSQL assigns it via the
 * {@code event_destinations.id} column's {@code DEFAULT uuidv7()} (research.md §1) — no
 * application-side ID generation exists anywhere in this codebase.
 *
 * <p>Only one of the {@code kafka*}/{@code httpUrl} field groups is populated at a time, matching
 * {@code type}; the database's {@code event_destinations_type_fields_check} CHECK constraint is
 * the structural guarantee, {@link EventDestinationService} the friendly-error guarantee
 * (FR-002-FR-004, FR-020).
 */
@Table("event_destinations")
public class EventDestination {

    @Id
    private UUID id;

    private String name;

    private EventDestinationType type;

    private boolean enabled;

    private String kafkaBootstrapServers;

    private String kafkaTopic;

    private String httpUrl;

    private String credential;

    private Instant createdAt;

    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public EventDestinationType getType() {
        return type;
    }

    public void setType(EventDestinationType type) {
        this.type = type;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKafkaBootstrapServers() {
        return kafkaBootstrapServers;
    }

    public void setKafkaBootstrapServers(String kafkaBootstrapServers) {
        this.kafkaBootstrapServers = kafkaBootstrapServers;
    }

    public String getKafkaTopic() {
        return kafkaTopic;
    }

    public void setKafkaTopic(String kafkaTopic) {
        this.kafkaTopic = kafkaTopic;
    }

    public String getHttpUrl() {
        return httpUrl;
    }

    public void setHttpUrl(String httpUrl) {
        this.httpUrl = httpUrl;
    }

    public String getCredential() {
        return credential;
    }

    public void setCredential(String credential) {
        this.credential = credential;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

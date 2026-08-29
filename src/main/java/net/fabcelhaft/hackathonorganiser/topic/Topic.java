package net.fabcelhaft.hackathonorganiser.topic;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A subject Participants can form a Group around (spec.md Key Entities: Topic; data-model.md
 * "Topic" — FR-010, FR-015).
 *
 * <p>{@code id} is left {@code null} on construction: PostgreSQL assigns it via the
 * {@code topics.id} column's {@code DEFAULT uuidv7()} (research.md §1) — no application-side ID
 * generation exists anywhere in this codebase.
 *
 * <p>{@code createdByUserId} is set once, at creation, and never reassigned afterward (FR-015):
 * the creator reference is retained even if that User's access is later revoked (edge case,
 * spec.md) — no code path in this feature clears or reassigns it.
 */
@Table("topics")
public class Topic {

    @Id
    private UUID id;

    private String name;

    private String description;

    private UUID createdByUserId;

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(UUID createdByUserId) {
        this.createdByUserId = createdByUserId;
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

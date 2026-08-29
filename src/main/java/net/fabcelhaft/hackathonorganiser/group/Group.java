package net.fabcelhaft.hackathonorganiser.group;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A formed team tied to exactly one Topic (spec.md Key Entities: Group; data-model.md "Group" —
 * FR-016, FR-016a, FR-016b).
 *
 * <p>{@code id} is left {@code null} on construction: PostgreSQL assigns it via the
 * {@code groups.id} column's {@code DEFAULT uuidv7()} (research.md §1) — no application-side ID
 * generation exists anywhere in this codebase.
 *
 * <p>"At most one active Group per Topic" (FR-016a) is enforced by a Postgres partial unique index
 * on {@code (topic_id) WHERE status = 'ACTIVE'} (research.md §4, schema.sql) — a
 * concurrency-safe guarantee that a service-layer pre-check alone cannot provide.
 */
@Table("groups")
public class Group {

    @Id
    private UUID id;

    private UUID topicId;

    private GroupStatus status;

    private Instant disbandedAt;

    private Instant createdAt;

    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTopicId() {
        return topicId;
    }

    public void setTopicId(UUID topicId) {
        this.topicId = topicId;
    }

    public GroupStatus getStatus() {
        return status;
    }

    public void setStatus(GroupStatus status) {
        this.status = status;
    }

    public Instant getDisbandedAt() {
        return disbandedAt;
    }

    public void setDisbandedAt(Instant disbandedAt) {
        this.disbandedAt = disbandedAt;
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

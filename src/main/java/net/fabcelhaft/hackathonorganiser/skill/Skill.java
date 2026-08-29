package net.fabcelhaft.hackathonorganiser.skill;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A named capability an Organiser can select for a Participant or a Topic (spec.md Key Entities:
 * Skill; data-model.md "Skill" — FR-008, FR-008a).
 *
 * <p>{@code id} is left {@code null} on construction: PostgreSQL assigns it via the
 * {@code skills.id} column's {@code DEFAULT uuidv7()} (research.md §1) — no application-side ID
 * generation exists anywhere in this codebase.
 */
@Table("skills")
public class Skill {

    @Id
    private UUID id;

    private String name;

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

package net.fabcelhaft.hackathonorganiser.compliance;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * An Organiser-configured Compliance rule requiring at least {@code minimumDistinctValues}
 * distinct recorded values for one Custom Field across a Group's current members (spec.md Key
 * Entities: Custom Field Diversity Requirement; data-model.md "Custom Field Diversity Requirement"
 * — FR-011, FR-011d, FR-012a).
 *
 * <p>{@code id} is left {@code null} on construction: PostgreSQL assigns it via the {@code
 * compliance_diversity_requirements.id} column's {@code DEFAULT uuidv7()} (research.md §1) — no
 * application-side ID generation exists anywhere in this codebase.
 *
 * <p>At most one requirement may exist per {@code customFieldDefinitionId} — enforced by a
 * Postgres unique index (schema.sql, research.md §3), the concurrency-safe backstop to {@link
 * ComplianceService#addRequirement}'s own pre-check.
 */
@Table("compliance_diversity_requirements")
public class ComplianceDiversityRequirement {

    @Id
    private UUID id;

    private UUID customFieldDefinitionId;

    private int minimumDistinctValues;

    private Instant createdAt;

    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getCustomFieldDefinitionId() {
        return customFieldDefinitionId;
    }

    public void setCustomFieldDefinitionId(UUID customFieldDefinitionId) {
        this.customFieldDefinitionId = customFieldDefinitionId;
    }

    public int getMinimumDistinctValues() {
        return minimumDistinctValues;
    }

    public void setMinimumDistinctValues(int minimumDistinctValues) {
        this.minimumDistinctValues = minimumDistinctValues;
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

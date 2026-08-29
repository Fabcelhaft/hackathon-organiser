package net.fabcelhaft.hackathonorganiser.customfield;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A selectable option belonging to a {@link CustomFieldType#MULTI_SELECT}
 * {@link CustomFieldDefinition} (data-model.md "Custom Field Option" — FR-012, FR-012b).
 *
 * <p>{@code id} is left {@code null} on construction: PostgreSQL assigns it via the
 * {@code custom_field_options.id} column's {@code DEFAULT uuidv7()} (research.md §1) — no
 * application-side ID generation exists anywhere in this codebase.
 */
@Table("custom_field_options")
public class CustomFieldOption {

    @Id
    private UUID id;

    private UUID customFieldDefinitionId;

    private String label;

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

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
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

package net.fabcelhaft.hackathonorganiser.customfield;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A field an Organiser defines for Participants to fill in — either free-text or a multi-select
 * from a configured option list (spec.md Key Entities: Custom Field; data-model.md "Custom Field
 * Definition" — FR-011, FR-012, FR-012a, FR-026).
 *
 * <p>{@code id} is left {@code null} on construction: PostgreSQL assigns it via the
 * {@code custom_field_definitions.id} column's {@code DEFAULT uuidv7()} (research.md §1) — no
 * application-side ID generation exists anywhere in this codebase.
 *
 * <p>{@code public_} is named with a trailing underscore because {@code public} is a reserved
 * Java keyword; {@link Column} maps it back onto the plain {@code public} database column
 * (data-model.md "Custom Field Definition", FR-016).
 */
@Table("custom_field_definitions")
public class CustomFieldDefinition {

    @Id
    private UUID id;

    private String label;

    private CustomFieldType fieldType;

    private boolean required;

    @Column("public")
    private boolean public_;

    private boolean overview;

    private boolean enabled;

    private Instant createdAt;

    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public CustomFieldType getFieldType() {
        return fieldType;
    }

    public void setFieldType(CustomFieldType fieldType) {
        this.fieldType = fieldType;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public boolean isPublic_() {
        return public_;
    }

    public void setPublic_(boolean public_) {
        this.public_ = public_;
    }

    public boolean isOverview() {
        return overview;
    }

    public void setOverview(boolean overview) {
        this.overview = overview;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

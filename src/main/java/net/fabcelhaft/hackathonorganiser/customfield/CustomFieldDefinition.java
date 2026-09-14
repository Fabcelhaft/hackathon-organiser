package net.fabcelhaft.hackathonorganiser.customfield;

import java.time.Instant;
import java.util.Comparator;
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
 *
 * <p>{@code sortIndex} (feature 009, FR-001) is the Organiser-set display precedence — lower first,
 * default 0 — that together with {@code label} and {@code createdAt} fixes this definition's
 * position in every listing via {@link #DISPLAY_ORDER}.
 */
@Table("custom_field_definitions")
public class CustomFieldDefinition {

    /**
     * The single display-order rule every view listing Custom Field Definitions uses (feature 009,
     * FR-007, FR-012; research.md §2): ascending {@code sortIndex}, then {@code label}
     * case-insensitively (the same comparator the Participants directory applies to display
     * names), then {@code createdAt} oldest-first as the tie-break for identical labels. A {@code
     * null} {@code createdAt} (unit-test fixtures only — the column is {@code NOT NULL}) sorts
     * last within its group rather than throwing.
     */
    public static final Comparator<CustomFieldDefinition> DISPLAY_ORDER =
            Comparator.comparingInt(CustomFieldDefinition::getSortIndex)
                    .thenComparing(CustomFieldDefinition::getLabel, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(
                            CustomFieldDefinition::getCreatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    @Id
    private UUID id;

    private String label;

    private CustomFieldType fieldType;

    private boolean required;

    @Column("public")
    private boolean public_;

    private boolean overview;

    private boolean enabled;

    private int sortIndex;

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

    public int getSortIndex() {
        return sortIndex;
    }

    public void setSortIndex(int sortIndex) {
        this.sortIndex = sortIndex;
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

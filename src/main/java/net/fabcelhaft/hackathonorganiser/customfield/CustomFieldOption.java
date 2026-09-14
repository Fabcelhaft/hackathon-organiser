package net.fabcelhaft.hackathonorganiser.customfield;

import java.time.Instant;
import java.util.Comparator;
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
 *
 * <p>{@code sortIndex} (feature 009, User Story 4, FR-015) is the Organiser-set display precedence
 * of this option inside its field — lower first, default 0 — see {@link #DISPLAY_ORDER}.
 */
@Table("custom_field_options")
public class CustomFieldOption {

    /**
     * The single display-order rule for the options of one definition (feature 009, FR-018;
     * research.md §7), mirroring {@link CustomFieldDefinition#DISPLAY_ORDER}: ascending {@code
     * sortIndex}, then {@code label} case-insensitively, then {@code createdAt} oldest-first, with a
     * {@code null} {@code createdAt} sorting last within its group.
     */
    public static final Comparator<CustomFieldOption> DISPLAY_ORDER =
            Comparator.comparingInt(CustomFieldOption::getSortIndex)
                    .thenComparing(CustomFieldOption::getLabel, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(
                            CustomFieldOption::getCreatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    @Id
    private UUID id;

    private UUID customFieldDefinitionId;

    private String label;

    private int sortIndex;

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

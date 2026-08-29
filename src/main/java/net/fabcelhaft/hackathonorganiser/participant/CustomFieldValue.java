package net.fabcelhaft.hackathonorganiser.participant;

import java.time.Instant;
import java.util.UUID;

/**
 * A Participant's own answer to a {@code CustomFieldDefinition} (spec.md Key Entities: Custom
 * Field; data-model.md "Custom Field Value" — FR-013, FR-014).
 *
 * <p>Backs the composite-key {@code custom_field_values} table
 * ({@code PRIMARY KEY (participant_id, custom_field_definition_id)}). Per research.md §4 and the
 * data-model.md note that composite-key "association carrying a payload" tables are intentionally
 * excluded from FR-025's UUIDv7 list, Spring Data R2DBC's {@code ReactiveCrudRepository} cannot
 * back this table directly — it requires a single-column {@code @Id}. {@link ParticipantService}
 * therefore manipulates {@code custom_field_values} directly via {@code DatabaseClient}; this
 * class is a plain, repository-independent data holder for that row shape rather than a
 * {@code @Table}-annotated entity.
 */
public class CustomFieldValue {

    private UUID participantId;

    private UUID customFieldDefinitionId;

    private String freeTextValue;

    private Instant createdAt;

    private Instant updatedAt;

    public CustomFieldValue() {}

    public CustomFieldValue(
            UUID participantId,
            UUID customFieldDefinitionId,
            String freeTextValue,
            Instant createdAt,
            Instant updatedAt) {
        this.participantId = participantId;
        this.customFieldDefinitionId = customFieldDefinitionId;
        this.freeTextValue = freeTextValue;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getParticipantId() {
        return participantId;
    }

    public void setParticipantId(UUID participantId) {
        this.participantId = participantId;
    }

    public UUID getCustomFieldDefinitionId() {
        return customFieldDefinitionId;
    }

    public void setCustomFieldDefinitionId(UUID customFieldDefinitionId) {
        this.customFieldDefinitionId = customFieldDefinitionId;
    }

    public String getFreeTextValue() {
        return freeTextValue;
    }

    public void setFreeTextValue(String freeTextValue) {
        this.freeTextValue = freeTextValue;
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

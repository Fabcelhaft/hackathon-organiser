package net.fabcelhaft.hackathonorganiser.audit;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One immutable record of a change to a Topic or Participant (spec.md Key Entities: Audit Entry;
 * data-model.md "Audit Entry" — FR-001-FR-011a).
 *
 * <p>{@code id} is left {@code null} on construction: PostgreSQL assigns it via the
 * {@code audit_entries.id} column's {@code DEFAULT uuidv7()} (research.md §1) — no
 * application-side ID generation exists anywhere in this codebase.
 *
 * <p>No update or delete method is ever added to {@link AuditEntryRepository} (FR-010):
 * immutability is enforced by omission, the same convention this codebase already uses for every
 * other "never changes after the fact" field (research.md §6).
 */
@Table("audit_entries")
public class AuditEntry {

    @Id
    private UUID id;

    private AuditEventType eventType;

    private UUID actorUserId;

    private boolean organiser;

    private Instant occurredAt;

    private AuditSubjectType subjectType;

    private UUID subjectId;

    private String subjectLabel;

    private String oldValue;

    private String newValue;

    private UUID actionId;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public AuditEventType getEventType() {
        return eventType;
    }

    public void setEventType(AuditEventType eventType) {
        this.eventType = eventType;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(UUID actorUserId) {
        this.actorUserId = actorUserId;
    }

    public boolean isOrganiser() {
        return organiser;
    }

    public void setOrganiser(boolean organiser) {
        this.organiser = organiser;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public AuditSubjectType getSubjectType() {
        return subjectType;
    }

    public void setSubjectType(AuditSubjectType subjectType) {
        this.subjectType = subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(UUID subjectId) {
        this.subjectId = subjectId;
    }

    public String getSubjectLabel() {
        return subjectLabel;
    }

    public void setSubjectLabel(String subjectLabel) {
        this.subjectLabel = subjectLabel;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public UUID getActionId() {
        return actionId;
    }

    public void setActionId(UUID actionId) {
        this.actionId = actionId;
    }
}

package net.fabcelhaft.hackathonorganiser.organisersettings;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The single global row controlling self-registration, self-revocation, and topic-approval
 * gating (spec.md Key Entities: Organiser Settings; data-model.md "Organiser Settings" — FR-023,
 * FR-023a).
 *
 * <p>{@code id} is left {@code null} on construction: PostgreSQL assigns it via the
 * {@code organiser_settings.id} column's {@code DEFAULT uuidv7()} (research.md §1) — no
 * application-side ID generation exists anywhere in this codebase.
 *
 * <p>{@code singleton} is always {@code true}: the unique index on this column (schema.sql,
 * research.md §4) guarantees exactly one row ever exists, seeded once at startup.
 */
@Table("organiser_settings")
public class OrganiserSettings {

    @Id
    private UUID id;

    private boolean singleton;

    private boolean selfRegistrationEnabled;

    private boolean selfRevocationEnabled;

    private boolean topicApprovalRequired;

    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public boolean isSingleton() {
        return singleton;
    }

    public void setSingleton(boolean singleton) {
        this.singleton = singleton;
    }

    public boolean isSelfRegistrationEnabled() {
        return selfRegistrationEnabled;
    }

    public void setSelfRegistrationEnabled(boolean selfRegistrationEnabled) {
        this.selfRegistrationEnabled = selfRegistrationEnabled;
    }

    public boolean isSelfRevocationEnabled() {
        return selfRevocationEnabled;
    }

    public void setSelfRevocationEnabled(boolean selfRevocationEnabled) {
        this.selfRevocationEnabled = selfRevocationEnabled;
    }

    public boolean isTopicApprovalRequired() {
        return topicApprovalRequired;
    }

    public void setTopicApprovalRequired(boolean topicApprovalRequired) {
        this.topicApprovalRequired = topicApprovalRequired;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

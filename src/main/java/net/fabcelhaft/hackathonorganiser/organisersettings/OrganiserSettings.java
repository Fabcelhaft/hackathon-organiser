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

    private Integer maxRegistrations;

    private boolean selfEditEnabled;

    private boolean skillVisibilityEnabled;

    private DirectoryAudience participantsDirectoryAudience;

    private int maxGroupMembers;

    private Integer minGroupMembers;

    private boolean topicJoiningEnabled;

    private SkillDisplayMode skillDisplayMode;

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

    public Integer getMaxRegistrations() {
        return maxRegistrations;
    }

    public void setMaxRegistrations(Integer maxRegistrations) {
        this.maxRegistrations = maxRegistrations;
    }

    public boolean isSelfEditEnabled() {
        return selfEditEnabled;
    }

    public void setSelfEditEnabled(boolean selfEditEnabled) {
        this.selfEditEnabled = selfEditEnabled;
    }

    public boolean isSkillVisibilityEnabled() {
        return skillVisibilityEnabled;
    }

    public void setSkillVisibilityEnabled(boolean skillVisibilityEnabled) {
        this.skillVisibilityEnabled = skillVisibilityEnabled;
    }

    public DirectoryAudience getParticipantsDirectoryAudience() {
        return participantsDirectoryAudience;
    }

    public void setParticipantsDirectoryAudience(DirectoryAudience participantsDirectoryAudience) {
        this.participantsDirectoryAudience = participantsDirectoryAudience;
    }

    public int getMaxGroupMembers() {
        return maxGroupMembers;
    }

    public void setMaxGroupMembers(int maxGroupMembers) {
        this.maxGroupMembers = maxGroupMembers;
    }

    public Integer getMinGroupMembers() {
        return minGroupMembers;
    }

    public void setMinGroupMembers(Integer minGroupMembers) {
        this.minGroupMembers = minGroupMembers;
    }

    public boolean isTopicJoiningEnabled() {
        return topicJoiningEnabled;
    }

    public void setTopicJoiningEnabled(boolean topicJoiningEnabled) {
        this.topicJoiningEnabled = topicJoiningEnabled;
    }

    public SkillDisplayMode getSkillDisplayMode() {
        return skillDisplayMode;
    }

    public void setSkillDisplayMode(SkillDisplayMode skillDisplayMode) {
        this.skillDisplayMode = skillDisplayMode;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

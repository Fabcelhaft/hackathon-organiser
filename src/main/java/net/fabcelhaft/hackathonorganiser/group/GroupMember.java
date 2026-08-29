package net.fabcelhaft.hackathonorganiser.group;

import java.time.Instant;
import java.util.UUID;

/**
 * A Group's membership record for one Participant (spec.md Key Entities: Group; data-model.md
 * "Group Member" — FR-017, FR-016b).
 *
 * <p>Backs the composite-key {@code group_members} table
 * ({@code PRIMARY KEY (group_id, participant_id)}). Per research.md §4 and the data-model.md note
 * that composite-key "association carrying a payload" tables are intentionally excluded from
 * FR-025's UUIDv7 list, Spring Data R2DBC's {@code ReactiveCrudRepository} cannot back this table
 * directly — it requires a single-column {@code @Id}. {@link GroupService} therefore manipulates
 * {@code group_members} directly via {@code DatabaseClient}; this class is a plain,
 * repository-independent data holder for that row shape rather than a {@code @Table}-annotated
 * entity — the same approach {@code CustomFieldValue} uses in the {@code participant} package for
 * the same reason.
 *
 * <p>{@code active} is flipped to {@code false} for every row belonging to a Group when that Group
 * is disbanded (FR-016b), preserving the historical membership record rather than deleting it.
 * "At most one active Group per Participant" (FR-017) is enforced by a Postgres partial unique
 * index on {@code (participant_id) WHERE active} (research.md §4, schema.sql).
 */
public class GroupMember {

    private UUID groupId;

    private UUID participantId;

    private boolean active;

    private Instant joinedAt;

    public GroupMember() {}

    public GroupMember(UUID groupId, UUID participantId, boolean active, Instant joinedAt) {
        this.groupId = groupId;
        this.participantId = participantId;
        this.active = active;
        this.joinedAt = joinedAt;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public void setGroupId(UUID groupId) {
        this.groupId = groupId;
    }

    public UUID getParticipantId() {
        return participantId;
    }

    public void setParticipantId(UUID participantId) {
        this.participantId = participantId;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }
}

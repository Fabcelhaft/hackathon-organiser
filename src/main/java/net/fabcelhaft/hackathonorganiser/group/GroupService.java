package net.fabcelhaft.hackathonorganiser.group;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantRepository;
import net.fabcelhaft.hackathonorganiser.topic.Topic;
import net.fabcelhaft.hackathonorganiser.topic.TopicRepository;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Organiser-facing operations on {@link Group} records (T056): create with an active-Topic guard
 * (FR-016a), add/remove member with an active-Participant guard (FR-017), and disband (FR-016b).
 *
 * <p>{@code group_members} is a composite-key "association carrying a payload" table that a
 * single-column-{@code @Id} {@link org.springframework.data.repository.reactive.ReactiveCrudRepository}
 * cannot back (research.md §4), so this service manipulates it directly via {@link DatabaseClient}
 * — the same approach {@code participant_skills}/{@code custom_field_values} use elsewhere in this
 * codebase for the same reason.
 *
 * <p>The "at most one active Group per Topic" (FR-016a) and "at most one active Group per
 * Participant" (FR-017) invariants are ultimately guaranteed by Postgres partial unique indexes
 * (schema.sql, research.md §4) — the pre-checks here exist purely to turn a would-be constraint
 * violation into a friendly {@link GroupConflictException} before the write is even attempted, and
 * to gracefully translate a lost race (the rare case where two concurrent requests both pass the
 * pre-check) into that same friendly error rather than letting a raw database exception surface.
 */
@Service
public class GroupService {

    private final GroupRepository groupRepository;
    private final TopicRepository topicRepository;
    private final ParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final DatabaseClient databaseClient;

    public GroupService(
            GroupRepository groupRepository,
            TopicRepository topicRepository,
            ParticipantRepository participantRepository,
            UserRepository userRepository,
            DatabaseClient databaseClient) {
        this.groupRepository = groupRepository;
        this.topicRepository = topicRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.databaseClient = databaseClient;
    }

    // --- Read views ----------------------------------------------------------------------------

    public Flux<GroupSummary> findAllSummaries() {
        return groupRepository
                .findAll()
                .concatMap(group -> topicName(group.getTopicId())
                        .map(topicName ->
                                new GroupSummary(group.getId(), group.getTopicId(), topicName, group.getStatus())));
    }

    /** The Topic picker for the new-Group form, restricted to Topics with no active Group (FR-016a). */
    public Flux<Topic> findTopicsWithoutActiveGroup() {
        return topicRepository
                .findAll()
                .filterWhen(topic -> groupRepository
                        .findByTopicIdAndStatus(topic.getId(), GroupStatus.ACTIVE)
                        .hasElement()
                        .map(hasActive -> !hasActive));
    }

    /** The Participant picker for the new-Group form and the detail view's add-member form. */
    public Flux<ParticipantOption> allParticipants() {
        return participantRepository
                .findAll()
                .concatMap(participant -> userRepository
                        .findById(participant.getUserId())
                        .map(User::getDisplayName)
                        .defaultIfEmpty("Unknown user")
                        .map(displayName -> new ParticipantOption(participant.getId(), displayName)));
    }

    /** Whether — and which — active Group currently exists for a Topic (contracts/topic-management.md). */
    public Mono<Group> findActiveGroupForTopic(UUID topicId) {
        return groupRepository.findByTopicIdAndStatus(topicId, GroupStatus.ACTIVE);
    }

    /**
     * The Participant's current active Group, if any (research.md §10) — a thin public wrapper
     * around the {@code group_members}-querying logic that already exists privately as
     * {@link #findActiveGroupIdForParticipant}, extended to also fetch the {@link Group} row
     * itself. Used by {@code ParticipantService.selfRevoke} (FR-007a) to find the membership to
     * remove; completes empty if the Participant has no active Group.
     */
    public Mono<Group> findActiveGroupForParticipant(UUID participantId) {
        return findActiveGroupIdForParticipant(participantId).flatMap(groupRepository::findById);
    }

    /**
     * A Group's detail-view read model: the Group itself, its Topic's name, and its current +
     * historical members (former members remain visible after a disband, FR-016b). Completes empty
     * if no Group exists with the given id.
     */
    public Mono<GroupDetail> findDetail(UUID id) {
        return groupRepository.findById(id).flatMap(group -> topicName(group.getTopicId())
                .flatMap(topicName -> loadMembers(id).map(members -> {
                    List<MemberView> current = members.stream().filter(MemberView::active).toList();
                    List<MemberView> former =
                            members.stream().filter(m -> !m.active()).toList();
                    return new GroupDetail(group, topicName, current, former);
                })));
    }

    // --- Create (FR-016a) --------------------------------------------------------------------------

    /**
     * Creates a new Group for a Topic, completing empty (translated to 404 by the controller,
     * contracts/group-management.md) if {@code topicId} is unknown, and rejecting a {@code
     * topicId} that already has an active Group (FR-016a) with a friendly
     * {@link GroupConflictException}. Any {@code participantIds} are then added as initial
     * members via the same guard {@link #addMember(UUID, UUID)} applies (FR-017).
     */
    public Mono<Group> create(UUID topicId, List<UUID> participantIds) {
        List<UUID> ids = distinct(participantIds);
        if (topicId == null) {
            return Mono.error(new GroupConflictException("topic_id is required"));
        }
        return topicRepository.existsById(topicId).flatMap(topicExists -> {
            if (!topicExists) {
                return Mono.empty();
            }
            return groupRepository
                    .findByTopicIdAndStatus(topicId, GroupStatus.ACTIVE)
                    .<Group>flatMap(existing ->
                            Mono.error(new GroupConflictException("Topic already has a Group")))
                    .switchIfEmpty(Mono.defer(() -> groupRepository
                            .save(newGroup(topicId))
                            .flatMap(group -> addInitialMembers(group, ids))));
        });
    }

    private Mono<Group> addInitialMembers(Group group, List<UUID> participantIds) {
        return Flux.fromIterable(participantIds)
                .concatMap(participantId -> addMember(group.getId(), participantId))
                .then(Mono.just(group));
    }

    // --- Members (FR-017) -----------------------------------------------------------------------

    /**
     * Adds a Participant to a Group, completing empty (404) if {@code groupId} is unknown, and
     * rejecting with a friendly {@link GroupConflictException} if the Group is {@code DISBANDED},
     * {@code participantId} is unknown, or the Participant already belongs to a different active
     * Group (FR-017).
     */
    public Mono<Group> addMember(UUID groupId, UUID participantId) {
        return groupRepository.findById(groupId).flatMap(group -> {
            if (group.getStatus() == GroupStatus.DISBANDED) {
                return Mono.error(new GroupConflictException("Cannot add a member to a disbanded Group"));
            }
            if (participantId == null) {
                return Mono.error(new GroupConflictException("participant_id is required"));
            }
            return participantRepository.existsById(participantId).flatMap(exists -> {
                if (!exists) {
                    return Mono.error(new GroupConflictException("Unknown participant: " + participantId));
                }
                return findActiveGroupIdForParticipant(participantId)
                        .flatMap(activeGroupId -> {
                            if (!activeGroupId.equals(group.getId())) {
                                return Mono.<Group>error(new GroupConflictException(
                                        "This participant already belongs to a different active Group"));
                            }
                            return Mono.just(group);
                        })
                        .switchIfEmpty(Mono.defer(
                                () -> insertMembership(group.getId(), participantId).thenReturn(group)));
            });
        });
    }

    /**
     * Removes a Participant from a Group, completing empty (404) if {@code groupId} is unknown or
     * the membership isn't currently active (contracts/group-management.md).
     */
    public Mono<Group> removeMember(UUID groupId, UUID participantId) {
        return groupRepository.findById(groupId).flatMap(group -> isActiveMember(groupId, participantId)
                .flatMap(isActive -> {
                    if (!isActive) {
                        return Mono.empty();
                    }
                    return databaseClient
                            .sql(
                                    "UPDATE group_members SET active = false"
                                            + " WHERE group_id = :gid AND participant_id = :pid")
                            .bind("gid", groupId)
                            .bind("pid", participantId)
                            .then()
                            .thenReturn(group);
                }));
    }

    // --- Disband (FR-016b) -------------------------------------------------------------------------

    /**
     * Disbands a Group: flips its {@code status} to {@code DISBANDED}, sets {@code disbandedAt},
     * and flips every one of its memberships to {@code active = false} (FR-016b) — all in one
     * reactive chain. Completes empty if {@code groupId} is unknown; rejects with a friendly
     * {@link GroupConflictException} if the Group is already {@code DISBANDED}.
     */
    public Mono<Group> disband(UUID groupId) {
        return groupRepository.findById(groupId).flatMap(group -> {
            if (group.getStatus() == GroupStatus.DISBANDED) {
                return Mono.error(new GroupConflictException("This Group is already disbanded"));
            }
            Instant now = Instant.now();
            group.setStatus(GroupStatus.DISBANDED);
            group.setDisbandedAt(now);
            group.setUpdatedAt(now);
            return groupRepository
                    .save(group)
                    .flatMap(saved -> databaseClient
                            .sql("UPDATE group_members SET active = false WHERE group_id = :gid")
                            .bind("gid", groupId)
                            .then()
                            .thenReturn(saved));
        });
    }

    // --- DatabaseClient helpers against group_members -------------------------------------------

    private Mono<UUID> findActiveGroupIdForParticipant(UUID participantId) {
        return databaseClient
                .sql("SELECT group_id FROM group_members WHERE participant_id = :pid AND active = true")
                .bind("pid", participantId)
                .mapValue(UUID.class)
                .one();
    }

    private Mono<Boolean> isActiveMember(UUID groupId, UUID participantId) {
        return databaseClient
                .sql("SELECT active FROM group_members WHERE group_id = :gid AND participant_id = :pid")
                .bind("gid", groupId)
                .bind("pid", participantId)
                .mapValue(Boolean.class)
                .one()
                .defaultIfEmpty(false);
    }

    private Mono<Void> insertMembership(UUID groupId, UUID participantId) {
        return databaseClient
                .sql(
                        "INSERT INTO group_members (group_id, participant_id, active, joined_at)"
                                + " VALUES (:gid, :pid, true, :now)"
                                + " ON CONFLICT (group_id, participant_id)"
                                + " DO UPDATE SET active = true, joined_at = EXCLUDED.joined_at")
                .bind("gid", groupId)
                .bind("pid", participantId)
                .bind("now", Instant.now())
                .then();
    }

    private Mono<List<MemberView>> loadMembers(UUID groupId) {
        return databaseClient
                .sql("SELECT participant_id, active, joined_at FROM group_members WHERE group_id = :gid"
                        + " ORDER BY joined_at")
                .bind("gid", groupId)
                .map(row -> new Object[] {
                    row.get("participant_id", UUID.class), row.get("active", Boolean.class), row.get(
                            "joined_at", Instant.class)
                })
                .all()
                .concatMap(row -> {
                    UUID participantId = (UUID) row[0];
                    boolean active = (Boolean) row[1];
                    Instant joinedAt = (Instant) row[2];
                    return participantDisplayName(participantId)
                            .map(name -> new MemberView(participantId, name, active, joinedAt));
                })
                .collectList();
    }

    private Mono<String> participantDisplayName(UUID participantId) {
        return participantRepository
                .findById(participantId)
                .flatMap(participant -> userRepository
                        .findById(participant.getUserId())
                        .map(User::getDisplayName)
                        .defaultIfEmpty("Unknown user"))
                .defaultIfEmpty("Unknown participant");
    }

    private Mono<String> topicName(UUID topicId) {
        return topicRepository.findById(topicId).map(Topic::getName).defaultIfEmpty("Unknown topic");
    }

    private static List<UUID> distinct(List<UUID> ids) {
        return ids == null ? List.of() : ids.stream().distinct().toList();
    }

    private Group newGroup(UUID topicId) {
        Group group = new Group();
        group.setTopicId(topicId);
        group.setStatus(GroupStatus.ACTIVE);
        Instant now = Instant.now();
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        return group;
    }

    // --- Read-model view types -------------------------------------------------------------------

    public record GroupSummary(UUID id, UUID topicId, String topicName, GroupStatus status) {}

    public record ParticipantOption(UUID id, String displayName) {}

    public record MemberView(UUID participantId, String participantDisplayName, boolean active, Instant joinedAt) {}

    public record GroupDetail(
            Group group, String topicName, List<MemberView> currentMembers, List<MemberView> formerMembers) {}
}

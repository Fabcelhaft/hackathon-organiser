package net.fabcelhaft.hackathonorganiser.group;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.audit.AuditActor;
import net.fabcelhaft.hackathonorganiser.audit.AuditEventType;
import net.fabcelhaft.hackathonorganiser.audit.AuditService;
import net.fabcelhaft.hackathonorganiser.audit.AuditSubjectType;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceService;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceStatus;
import net.fabcelhaft.hackathonorganiser.event.EventPayloadFactory;
import net.fabcelhaft.hackathonorganiser.event.EventPublisher;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantRepository;
import net.fabcelhaft.hackathonorganiser.topic.Topic;
import net.fabcelhaft.hackathonorganiser.topic.TopicRepository;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
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
 *
 * <p><b>Audit (006-audit-trail, FR-001, FR-004, FR-004a):</b> every event that affects a Group is
 * recorded against that Group's own Topic instead — Groups have no audit trail of their own
 * (data-model.md "Group", research.md §3/§9) — via {@link AuditService}, threaded through as an
 * explicit {@link AuditActor} parameter on every mutating method here. {@link #addMember}/{@link
 * #removeMember} additionally acquire a Participant-scoped advisory lock (research.md §5) and
 * write a linked {@code JOINED}/{@code LEFT} pair sharing one {@code actionId} — identical whether
 * reached via {@link #join}/{@link #leave} or the organiser's direct add/remove-member route.
 */
@Service
public class GroupService {

    private final GroupRepository groupRepository;
    private final TopicRepository topicRepository;
    private final ParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final DatabaseClient databaseClient;
    private final OrganiserSettingsService organiserSettingsService;
    private final TransactionalOperator transactionalOperator;
    private final ComplianceService complianceService;
    private final AuditService auditService;
    private final EventPublisher eventPublisher;
    private final EventPayloadFactory eventPayloadFactory;

    public GroupService(
            GroupRepository groupRepository,
            TopicRepository topicRepository,
            ParticipantRepository participantRepository,
            UserRepository userRepository,
            DatabaseClient databaseClient,
            OrganiserSettingsService organiserSettingsService,
            TransactionalOperator transactionalOperator,
            ComplianceService complianceService,
            AuditService auditService,
            EventPublisher eventPublisher,
            EventPayloadFactory eventPayloadFactory) {
        this.groupRepository = groupRepository;
        this.topicRepository = topicRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.databaseClient = databaseClient;
        this.organiserSettingsService = organiserSettingsService;
        this.transactionalOperator = transactionalOperator;
        this.complianceService = complianceService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.eventPayloadFactory = eventPayloadFactory;
    }

    // --- Read views ----------------------------------------------------------------------------

    public Flux<GroupSummary> findAllSummaries() {
        return groupRepository
                .findAll()
                .concatMap(group -> topicName(group.getTopicId())
                        .flatMap(topicName -> complianceStatusForSummary(group)
                                .map(complianceStatus -> new GroupSummary(
                                        group.getId(), group.getTopicId(), topicName, group.getStatus(),
                                        complianceStatus))));
    }

    /**
     * A list-row's Compliance status ({@link #findAllSummaries}): {@link Optional#empty()} for a
     * {@code DISBANDED} Group, since every one of its memberships is already {@code active = false}
     * (FR-016b) — evaluating that against the ruleset would misleadingly read as "0 members", not
     * as "no longer applicable". Otherwise the same {@link ComplianceService#evaluate} contract
     * {@code GroupController}'s own detail view already uses (research.md §5).
     */
    private Mono<Optional<ComplianceStatus>> complianceStatusForSummary(Group group) {
        if (group.getStatus() != GroupStatus.ACTIVE) {
            return Mono.just(Optional.empty());
        }
        return activeMemberParticipantIds(group.getId())
                .flatMap(memberIds -> complianceService.evaluate(group, memberIds))
                .map(Optional::of);
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
    public Mono<Group> create(UUID topicId, List<UUID> participantIds, AuditActor actor) {
        Mono<Group> chain =
                createGroupRow(topicId, participantIds, actor).flatMap(group -> recordCreated(group, actor));
        return transactionalOperator.transactional(chain);
    }

    /**
     * The unaudited-for-itself core of {@link #create}, also reused by {@link #join}'s first-
     * joiner path: any initial {@code participantIds} are added via {@link #addMember}, which
     * records its own {@code JOINED} pair per member — the Group formation itself only gets a
     * {@code CREATED} entry from {@link #create} (organiser-initiated direct-create), never from
     * {@link #join}'s call here (data-model.md "Group"/{@code create}).
     */
    private Mono<Group> createGroupRow(UUID topicId, List<UUID> participantIds, AuditActor actor) {
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
                            .flatMap(group -> addInitialMembers(group, ids, actor))
                            .flatMap(group -> Mono.defer(() -> publishGroupFormed(group)).onErrorResume(ex -> Mono.empty()).thenReturn(group))));
        });
    }

    /** Publishes {@code GROUP_FORMED} once, right after a new Group (and any initial members) exists (FR-010a). */
    private Mono<Void> publishGroupFormed(Group group) {
        return Mono.zip(activeMemberParticipantIds(group.getId()), topicRepository.findById(group.getTopicId()))
                .flatMap(tuple -> complianceService
                        .evaluate(group, tuple.getT1())
                        .doOnNext(status -> eventPublisher.publish(eventPayloadFactory.groupFormed(group, status, tuple.getT2()))))
                .then();
    }

    private Mono<Group> recordCreated(Group group, AuditActor actor) {
        return topicName(group.getTopicId())
                .flatMap(name -> auditService.record(
                        AuditEventType.CREATED,
                        actor,
                        AuditSubjectType.TOPIC,
                        group.getTopicId(),
                        name,
                        null,
                        null,
                        null))
                .thenReturn(group);
    }

    // --- Join (Story 3, FR-007-FR-013a, research.md §2) -----------------------------------------

    /**
     * The race-safe self-service Join entry point: inside one {@link TransactionalOperator}-wrapped
     * transaction, acquires a session-scoped Postgres advisory lock keyed on {@code topicId} —
     * covering both the "first joiner creates the Group" and "joining an existing Group" races with
     * one mechanism (research.md §2) — then either creates a new Group with {@code participantId}
     * as its sole member (reusing {@link #create}'s existing "no active Group yet" path) or, for an
     * existing active Group, adds the Participant once re-read capacity/override checks pass.
     * Completes empty (404-mappable) for an unknown {@code topicId}; rejects with a friendly
     * {@link GroupConflictException} ("This Topic is full") if the join would bring the Group's
     * member count to or beyond {@code maxGroupMembers} and it carries no {@code
     * complianceOverride} (FR-013), or if {@code participantId} already belongs to a different
     * active Group (FR-010, reusing {@link #addMember}'s existing guard).
     */
    public Mono<Group> join(UUID topicId, UUID participantId, AuditActor actor) {
        Mono<Group> chain = acquireTopicJoinLock(topicId)
                .then(acquireParticipantJoinLock(participantId))
                .then(Mono.defer(() -> groupRepository.findByTopicIdAndStatus(topicId, GroupStatus.ACTIVE)))
                .flatMap(existing -> joinExistingGroup(existing, participantId, actor))
                .switchIfEmpty(Mono.defer(() -> createGroupRow(topicId, List.of(participantId), actor)));
        return transactionalOperator.transactional(chain);
    }

    private Mono<Group> joinExistingGroup(Group group, UUID participantId, AuditActor actor) {
        return activeMemberCount(group.getId())
                .flatMap(count -> organiserSettingsService.current().flatMap(settings -> {
                    boolean atOrAboveCapacity = count >= settings.getMaxGroupMembers();
                    if (atOrAboveCapacity && !group.isComplianceOverride()) {
                        return Mono.<Group>error(new GroupConflictException("This Topic is full"));
                    }
                    return addMemberChain(group.getId(), participantId, actor);
                }));
    }

    // --- Leave (Story 11, FR-037-FR-037e, research.md §14) --------------------------------------

    /**
     * The race-safe self-service Leave entry point: inside the *same* {@link TransactionalOperator}-
     * wrapped transaction and per-Topic advisory lock {@link #join} already acquires (not a second
     * lock), re-reads the Topic's active Group, removes {@code participantId}'s membership via the
     * already-existing {@link #removeMember}, and — only when {@link #activeMemberCount} is then
     * {@code 0} — disbands the Group via the already-existing {@link #disband} (FR-037c). No new SQL
     * is introduced by this method. Rejects with a friendly {@link GroupConflictException} if the
     * Topic has no active Group, or {@code participantId} is not currently one of its active members
     * (FR-037b).
     */
    public Mono<Group> leave(UUID topicId, UUID participantId, AuditActor actor) {
        Mono<Group> chain = acquireTopicJoinLock(topicId)
                .then(acquireParticipantJoinLock(participantId))
                .then(Mono.defer(() -> groupRepository.findByTopicIdAndStatus(topicId, GroupStatus.ACTIVE)))
                .switchIfEmpty(Mono.error(notCurrentlyAMember()))
                .flatMap(group -> removeMemberChain(group.getId(), participantId, actor)
                        .switchIfEmpty(Mono.error(notCurrentlyAMember()))
                        .flatMap(removed -> activeMemberCount(removed.getId())
                                .flatMap(count -> count == 0 ? disbandGroupRow(removed.getId()) : Mono.just(removed))));
        return transactionalOperator.transactional(chain);
    }

    private static GroupConflictException notCurrentlyAMember() {
        return new GroupConflictException("You are not currently a member of this Topic");
    }

    private Mono<Void> acquireTopicJoinLock(UUID topicId) {
        return databaseClient
                .sql("SELECT pg_advisory_xact_lock(hashtext('topic-join:' || :tid::text))")
                .bind("tid", topicId)
                .then();
    }

    /**
     * The Participant-scoped half of research.md §5's two-lock fix: acquired by {@link #join}/
     * {@link #leave} (always after {@link #acquireTopicJoinLock}, never before — fixed ordering
     * prevents deadlock) and, independently, by {@link #addMember}/{@link #removeMember}
     * themselves, so the organiser's direct add/remove-member routes — which never go through
     * {@link #join}/{@link #leave} — get the same protection. A session already holding this lock
     * (e.g. {@code addMember} re-acquiring it inside {@link #join}'s own transaction) simply
     * succeeds immediately: Postgres advisory locks are re-entrant within one session/transaction.
     */
    private Mono<Void> acquireParticipantJoinLock(UUID participantId) {
        return databaseClient
                .sql("SELECT pg_advisory_xact_lock(hashtext('participant-join:' || :pid::text))")
                .bind("pid", participantId)
                .then();
    }

    // --- Compliance override (Story 7, FR-015, FR-016) ------------------------------------------

    /**
     * Sets or clears a Group's compliance override (FR-015, FR-016); completes empty (404) for an
     * unknown {@code groupId}. No other guard: an Organiser may set or clear it regardless of
     * current member count or automatic compliance outcome.
     */
    public Mono<Group> setComplianceOverride(UUID groupId, boolean override, AuditActor actor) {
        return groupRepository.findById(groupId).flatMap(group -> {
            boolean oldValue = group.isComplianceOverride();
            group.setComplianceOverride(override);
            group.setUpdatedAt(Instant.now());
            return groupRepository
                    .save(group)
                    .flatMap(saved -> topicName(saved.getTopicId())
                            .flatMap(name -> auditService.record(
                                    AuditEventType.EDITED,
                                    actor,
                                    AuditSubjectType.TOPIC,
                                    saved.getTopicId(),
                                    name,
                                    Boolean.toString(oldValue),
                                    Boolean.toString(override),
                                    null))
                            .then(Mono.defer(() -> publishComplianceOverrideChanged(saved)).onErrorResume(ex -> Mono.empty()))
                            .thenReturn(saved));
        });
    }

    /** Publishes {@code GROUP_COMPLIANCE_CHANGED} with the Group's newly evaluated status (FR-015, FR-016). */
    private Mono<Void> publishComplianceOverrideChanged(Group group) {
        return Mono.zip(activeMemberParticipantIds(group.getId()), topicRepository.findById(group.getTopicId()))
                .flatMap(tuple -> complianceService
                        .evaluate(group, tuple.getT1())
                        .doOnNext(status ->
                                eventPublisher.publish(eventPayloadFactory.groupComplianceChanged(group, status, tuple.getT2()))))
                .then();
    }

    private Mono<Group> addInitialMembers(Group group, List<UUID> participantIds, AuditActor actor) {
        return Flux.fromIterable(participantIds)
                .concatMap(participantId -> addMemberChain(group.getId(), participantId, actor))
                .then(Mono.just(group));
    }

    // --- Members (FR-017) -----------------------------------------------------------------------

    /**
     * Adds a Participant to a Group, completing empty (404) if {@code groupId} is unknown, and
     * rejecting with a friendly {@link GroupConflictException} if the Group is {@code DISBANDED},
     * {@code participantId} is unknown, or the Participant already belongs to a different active
     * Group (FR-017).
     */
    public Mono<Group> addMember(UUID groupId, UUID participantId, AuditActor actor) {
        return transactionalOperator.transactional(addMemberChain(groupId, participantId, actor));
    }

    /**
     * The unwrapped core of {@link #addMember}: called directly (never re-wrapped) by every
     * internal caller that already runs inside its own {@link TransactionalOperator}-wrapped
     * chain ({@link #joinExistingGroup}, {@link #addInitialMembers}) — a second, nested {@code
     * transactionalOperator.transactional(...)} around an already-transactional Mono is not
     * guaranteed to reuse the same connection/transaction as the outer one, which would let the
     * participant-scoped advisory lock (research.md §5) release too early and reopen the very
     * cross-topic race it exists to close. Only {@link #addMember} itself — the entry point for
     * the organiser's direct, non-transactional {@code POST /organiser/groups/{id}/members} route
     * — applies the wrapper.
     */
    private Mono<Group> addMemberChain(UUID groupId, UUID participantId, AuditActor actor) {
        return groupRepository.findById(groupId).flatMap(group -> {
            if (group.getStatus() == GroupStatus.DISBANDED) {
                return Mono.error(new GroupConflictException("Cannot add a member to a disbanded Group"));
            }
            if (participantId == null) {
                return Mono.error(new GroupConflictException("participant_id is required"));
            }
            return acquireParticipantJoinLock(participantId)
                    .then(Mono.defer(() -> participantRepository.existsById(participantId)))
                    .flatMap(exists -> {
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
                                .switchIfEmpty(Mono.defer(() -> insertMembership(group.getId(), participantId)
                                        .then(recordMembershipPair(AuditEventType.JOINED, group, participantId, actor))
                                        .then(Mono.defer(() -> publishJoinEvents(group, participantId))
                                                .onErrorResume(ex -> Mono.empty()))
                                        .thenReturn(group)));
                    });
        });
    }

    /**
     * Publishes {@code PARTICIPANT_JOINED_TOPIC} (always) and, when the Group's evaluated
     * Compliance status just changed as a result of this join, {@code GROUP_COMPLIANCE_CHANGED}
     * too (spec.md Clarifications 2026-09-03 — the Event Type fires on any compliance-status
     * change, not only an explicit override). The "before" member list is derived from the
     * already-necessary post-insert query minus the just-added participant, rather than a second
     * query taken before the mutation — one less query, and the participant's own state never
     * gates the mutation's own success (this whole method is called only after the insert already
     * succeeded, and its caller isolates any failure here via {@code onErrorResume} — event
     * publishing must never be able to fail the underlying join, per FR-020a-1).
     */
    private Mono<Void> publishJoinEvents(Group group, UUID participantId) {
        return Mono.zip(topicRepository.findById(group.getTopicId()), participantRepository.findById(participantId))
                .flatMap(tuple -> {
                    Topic topic = tuple.getT1();
                    Participant participant = tuple.getT2();
                    eventPublisher.publish(eventPayloadFactory.participantJoinedTopic(topic, participant));
                    return activeMemberParticipantIds(group.getId())
                            .flatMap(newMemberIds -> {
                                List<UUID> oldMemberIds = newMemberIds.stream()
                                        .filter(id -> !id.equals(participantId))
                                        .toList();
                                return Mono.zip(
                                        complianceService.evaluate(group, oldMemberIds),
                                        complianceService.evaluate(group, newMemberIds));
                            })
                            .doOnNext(statuses -> {
                                if (statuses.getT1() != statuses.getT2()) {
                                    eventPublisher.publish(
                                            eventPayloadFactory.groupComplianceChanged(group, statuses.getT2(), topic));
                                }
                            });
                })
                .then();
    }

    /**
     * The shared {@code JOINED}/{@code LEFT} audit pair (FR-004, FR-004a): one entry against the
     * Group's own Topic (new value = the Participant's display name) and one against the
     * Participant (new value = the Topic's name), sharing a freshly-generated {@code actionId} —
     * identical whether reached via self-service {@link #join}/{@link #leave} or the organiser's
     * direct add/remove-member route (data-model.md "Group").
     */
    private Mono<Void> recordMembershipPair(AuditEventType type, Group group, UUID participantId, AuditActor actor) {
        UUID actionId = UUID.randomUUID();
        return Mono.zip(topicName(group.getTopicId()), participantDisplayName(participantId))
                .flatMap(names -> {
                    String topicNameStr = names.getT1();
                    String participantNameStr = names.getT2();
                    return auditService
                            .record(
                                    type,
                                    actor,
                                    AuditSubjectType.TOPIC,
                                    group.getTopicId(),
                                    topicNameStr,
                                    null,
                                    participantNameStr,
                                    actionId)
                            .then(auditService.record(
                                    type,
                                    actor,
                                    AuditSubjectType.PARTICIPANT,
                                    participantId,
                                    participantNameStr,
                                    null,
                                    topicNameStr,
                                    actionId));
                })
                .then();
    }

    /**
     * Removes a Participant from a Group, completing empty (404) if {@code groupId} is unknown or
     * the membership isn't currently active (contracts/group-management.md).
     */
    public Mono<Group> removeMember(UUID groupId, UUID participantId, AuditActor actor) {
        return transactionalOperator.transactional(removeMemberChain(groupId, participantId, actor));
    }

    /** The unwrapped core of {@link #removeMember} — see {@link #addMemberChain}'s Javadoc for why. */
    private Mono<Group> removeMemberChain(UUID groupId, UUID participantId, AuditActor actor) {
        return groupRepository.findById(groupId).flatMap(group -> acquireParticipantJoinLock(participantId)
                .then(Mono.defer(() -> isActiveMember(groupId, participantId)))
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
                            .then(recordMembershipPair(AuditEventType.LEFT, group, participantId, actor))
                            .then(Mono.defer(() -> publishLeaveEvents(group, participantId)).onErrorResume(ex -> Mono.empty()))
                            .thenReturn(group);
                }));
    }

    /**
     * Publishes {@code PARTICIPANT_LEFT_TOPIC} (always) and, when the Group's evaluated Compliance
     * status just changed and it still has at least one active member, {@code
     * GROUP_COMPLIANCE_CHANGED} too. When the last member has just left, Compliance no longer
     * applies (matches {@link #complianceStatusForSummary}'s existing DISBANDED-group convention),
     * so no {@code GROUP_COMPLIANCE_CHANGED} is published — {@link #leave}'s subsequent disbandment
     * publishes {@code GROUP_DISBANDED} instead. The "before" member list is the already-necessary
     * post-removal query plus the just-removed participant back in, mirroring {@link
     * #publishJoinEvents}'s no-extra-query approach; the caller isolates any failure here via
     * {@code onErrorResume} (FR-020a-1).
     */
    private Mono<Void> publishLeaveEvents(Group group, UUID participantId) {
        return Mono.zip(topicRepository.findById(group.getTopicId()), participantRepository.findById(participantId))
                .flatMap(tuple -> {
                    Topic topic = tuple.getT1();
                    Participant participant = tuple.getT2();
                    eventPublisher.publish(eventPayloadFactory.participantLeftTopic(topic, participant));
                    return activeMemberParticipantIds(group.getId()).flatMap(newMemberIds -> {
                        if (newMemberIds.isEmpty()) {
                            return Mono.empty();
                        }
                        List<UUID> oldMemberIds = new java.util.ArrayList<>(newMemberIds);
                        oldMemberIds.add(participantId);
                        return Mono.zip(
                                        complianceService.evaluate(group, oldMemberIds),
                                        complianceService.evaluate(group, newMemberIds))
                                .doOnNext(statuses -> {
                                    if (statuses.getT1() != statuses.getT2()) {
                                        eventPublisher.publish(
                                                eventPayloadFactory.groupComplianceChanged(group, statuses.getT2(), topic));
                                    }
                                });
                    });
                })
                .then();
    }

    // --- Disband (FR-016b) -------------------------------------------------------------------------

    /**
     * Disbands a Group: flips its {@code status} to {@code DISBANDED}, sets {@code disbandedAt},
     * and flips every one of its memberships to {@code active = false} (FR-016b) — all in one
     * reactive chain. Completes empty if {@code groupId} is unknown; rejects with a friendly
     * {@link GroupConflictException} if the Group is already {@code DISBANDED}.
     */
    public Mono<Group> disband(UUID groupId, AuditActor actor) {
        return disbandGroupRow(groupId).flatMap(group -> recordDisbanded(group, actor));
    }

    /**
     * The unaudited core of {@link #disband}, also reused by {@link #leave}'s automatic
     * last-member-departs disbandment — which is already captured by {@link #removeMember}'s own
     * {@code LEFT} audit pair, so this automatic side effect records no separate {@code DISBANDED}
     * entry of its own.
     */
    private Mono<Group> disbandGroupRow(UUID groupId) {
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
                            .then(Mono.defer(() -> publishGroupDisbanded(saved)).onErrorResume(ex -> Mono.empty()))
                            .thenReturn(saved));
        });
    }

    /**
     * Publishes {@code GROUP_DISBANDED} for any disbandment — an Organiser's direct {@link
     * #disband}, or {@link #leave}'s automatic last-member-departs disbandment (FR-010b's sibling
     * requirement: every occurrence covered by the catalog fires, however it happened). Compliance
     * no longer applies to a disbanded Group (research.md §5), so {@code complianceStatus} is
     * {@code null} in the payload.
     */
    private Mono<Void> publishGroupDisbanded(Group group) {
        return topicRepository
                .findById(group.getTopicId())
                .doOnNext(topic -> eventPublisher.publish(eventPayloadFactory.groupDisbanded(group, null, topic)))
                .then();
    }

    private Mono<Group> recordDisbanded(Group group, AuditActor actor) {
        return topicName(group.getTopicId())
                .flatMap(name -> auditService.record(
                        AuditEventType.DISBANDED,
                        actor,
                        AuditSubjectType.TOPIC,
                        group.getTopicId(),
                        name,
                        null,
                        null,
                        null))
                .thenReturn(group);
    }

    /**
     * The number of currently-active memberships for a Group (data-model.md "Group") — {@code 0}
     * for an unknown or empty Group. The single query shape shared by the Join capacity check
     * (research.md §2), {@code TopicDiscoveryService}'s participant-count columns, and {@code
     * ComplianceService}'s Maximum/Minimum rule evaluation, so there is exactly one definition of
     * "how many active members does this Group have" across the whole feature.
     */
    public Mono<Integer> activeMemberCount(UUID groupId) {
        return databaseClient
                .sql("SELECT count(*) FROM group_members WHERE group_id = :gid AND active")
                .bind("gid", groupId)
                .mapValue(Long.class)
                .one()
                .map(Long::intValue);
    }

    /** The participant ids of a Group's currently-active members — empty for an unknown/empty Group. */
    public Mono<List<UUID>> activeMemberParticipantIds(UUID groupId) {
        return databaseClient
                .sql("SELECT participant_id FROM group_members WHERE group_id = :gid AND active")
                .bind("gid", groupId)
                .mapValue(UUID.class)
                .all()
                .collectList();
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

    public record GroupSummary(
            UUID id, UUID topicId, String topicName, GroupStatus status, Optional<ComplianceStatus> complianceStatus) {}

    public record ParticipantOption(UUID id, String displayName) {}

    public record MemberView(UUID participantId, String participantDisplayName, boolean active, Instant joinedAt) {}

    public record GroupDetail(
            Group group, String topicName, List<MemberView> currentMembers, List<MemberView> formerMembers) {}
}

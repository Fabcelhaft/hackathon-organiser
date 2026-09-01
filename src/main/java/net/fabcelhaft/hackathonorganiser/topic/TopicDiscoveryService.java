package net.fabcelhaft.hackathonorganiser.topic;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceService;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceStatus;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettings;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.organisersettings.SkillDisplayMode;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantService;
import net.fabcelhaft.hackathonorganiser.skill.Skill;
import net.fabcelhaft.hackathonorganiser.skill.SkillRepository;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The shared read model behind the Home Page's capped, fullness-sorted topic table, the uncapped
 * Topic Overview, and the Topic Details view (research.md §7, §11, §13; FR-003–FR-006, FR-014,
 * FR-014a, FR-017, FR-030–FR-035). All three share the same "Topic + its active Group's member
 * count + its needed Skills after Skill Display Mode" computation and differ only in filtering,
 * ordering, cap, own-Topic pinning, and their Topic-Overview/Topic-Details-only columns (author,
 * Compliance, joined-Participants list).
 */
@Service
public class TopicDiscoveryService {

    private final TopicRepository topicRepository;
    private final GroupService groupService;
    private final OrganiserSettingsService organiserSettingsService;
    private final ComplianceService complianceService;
    private final SkillRepository skillRepository;
    private final UserRepository userRepository;
    private final ParticipantService participantService;
    private final DatabaseClient databaseClient;

    public TopicDiscoveryService(
            TopicRepository topicRepository,
            GroupService groupService,
            OrganiserSettingsService organiserSettingsService,
            ComplianceService complianceService,
            SkillRepository skillRepository,
            UserRepository userRepository,
            ParticipantService participantService,
            DatabaseClient databaseClient) {
        this.topicRepository = topicRepository;
        this.groupService = groupService;
        this.organiserSettingsService = organiserSettingsService;
        this.complianceService = complianceService;
        this.skillRepository = skillRepository;
        this.userRepository = userRepository;
        this.participantService = participantService;
        this.databaseClient = databaseClient;
    }

    /**
     * The Home Page's topic table (FR-003, FR-003a, FR-003b, FR-004, FR-033, FR-035): at most
     * {@code limit} rows total. The viewer's own Topics ({@code viewerUserId}'s authored Topics,
     * any approval status, any fullness) are always included, {@code pinned = true}, sorted
     * fullest-first among themselves and never truncated away; the remaining slots (never fewer
     * than 0) are filled from the existing fullness-sorted list of Approved Topics whose active
     * Group's member count is strictly below {@code maxGroupMembers} (a Topic with no Group counts
     * as {@code 0}), excluding any Topic already pinned. Each row's Skills are the
     * Skill-Display-Mode-filtered needed-Skill set intersected with {@code
     * viewerParticipantIdOrNull}'s own Skills — empty (never an error) for a viewer with no
     * Participant record or no matching Skills. Each row's {@code joinable} flag (FR-035) is
     * {@code true} only for an Approved Topic below the Maximum or carrying a compliance override,
     * so a pinned Pending or full Topic never claims to be joinable.
     */
    public Flux<OpenTopicRow> findOpenTopicsForHomePage(UUID viewerUserId, UUID viewerParticipantIdOrNull, int limit) {
        return organiserSettingsService
                .current()
                .flatMapMany(settings -> topicRepository
                        .findAll()
                        // Only ever fetch Group data for a Topic that could end up in the result:
                        // an Approved Topic (any author, for the fullness-sorted list) or one of the
                        // viewer's own (any status, for pinning, FR-033) — never another author's
                        // Pending Topic, exactly like the pre-pinning behavior.
                        .filter(topic -> topic.getApprovalStatus() == TopicApprovalStatus.APPROVED
                                || topic.getCreatedByUserId().equals(viewerUserId))
                        .concatMap(this::withActiveGroupAndCount)
                        .collectList()
                        .flatMapMany(all -> Flux.fromIterable(selectHomePageRows(all, viewerUserId, settings, limit)))
                        .concatMap(selection -> displayedNeededSkillIds(
                                        selection.tg().topic().getId(),
                                        selection.tg().group(),
                                        settings.getSkillDisplayMode())
                                .flatMap(displayedIds -> viewerOfferedSkillIds(viewerParticipantIdOrNull, displayedIds))
                                .flatMap(this::loadSkills)
                                .map(skills -> new OpenTopicRow(
                                        selection.tg().topic(),
                                        selection.tg().memberCount(),
                                        skills,
                                        selection.pinned(),
                                        isJoinable(selection.tg().topic(), selection.tg().memberCount(),
                                                selection.tg().group(), settings)))));
    }

    /**
     * The Topic Overview's table (FR-005, FR-006, FR-014, FR-014a, FR-034, FR-035): every Topic
     * visible to the caller (reusing {@link TopicService#isVisibleTo}'s Pending-visibility rule
     * verbatim), each row's needed Skills after Skill Display Mode — <em>not</em> intersected with
     * the viewer, unlike the Home Page — plus the author's display name, Compliance status
     * ({@code Optional.empty()} rendered as a blank cell, FR-014a), and {@code joinable}. The
     * viewer's own Topics are pinned above the rest ({@code pinned = true}); this method imposes no
     * cap, so pinning only reorders rows.
     */
    public Flux<OverviewRow> findTopicOverview(UUID viewerUserId, boolean viewerIsOrganiser) {
        return organiserSettingsService
                .current()
                .flatMapMany(settings -> topicRepository
                        .findAll()
                        .filter(topic -> TopicService.isVisibleTo(topic, viewerUserId, viewerIsOrganiser))
                        .collectList()
                        .flatMapMany(visible -> Flux.fromIterable(pinOwnTopicsFirst(visible, viewerUserId)))
                        .concatMap(p -> buildOverviewRow(p.topic(), settings, p.pinned())));
    }

    /**
     * The Topic Details view's read model (FR-030, FR-031, FR-032, FR-014a): empty (→ 404) for an
     * unknown Topic id or a Pending Topic the caller may not see, reusing the same
     * {@link TopicService#isVisibleTo} rule as {@link #findTopicOverview}. Otherwise, the Topic's
     * Name/Description (via the {@code Topic} itself), needed Skills after Skill Display Mode,
     * current participant count, Compliance status, and — for each currently joined Participant —
     * their {@link ParticipantService.ParticipantViewerDetail}, obtained by calling the existing
     * {@link ParticipantService#findDetailForViewer} once per member (research.md §10) rather than
     * re-deriving field/Skill visibility here. {@code isAuthor} drives the Topic Details template's
     * author-only edit link; {@code isMember} (Story 11, FR-037, research.md §14) drives its
     * member-only Leave form — {@code true} only when the viewer's own Participant record is among
     * the active Group's current members, {@code false} whenever there is no Group yet or the viewer
     * has no Participant record.
     */
    public Mono<TopicDetailView> findTopicDetail(UUID topicId, UUID viewerUserId, boolean viewerIsOrganiser) {
        return topicRepository
                .findById(topicId)
                .filter(topic -> TopicService.isVisibleTo(topic, viewerUserId, viewerIsOrganiser))
                .flatMap(topic -> organiserSettingsService.current().flatMap(settings -> groupService
                        .findActiveGroupForTopic(topicId)
                        .flatMap(group -> Mono.zip(
                                        groupService.activeMemberCount(group.getId()),
                                        groupService.activeMemberParticipantIds(group.getId()))
                                .flatMap(tuple -> complianceService
                                        .evaluate(group, tuple.getT2())
                                        .flatMap(status -> displayedNeededSkillIds(
                                                        topicId, group, settings.getSkillDisplayMode())
                                                .flatMap(this::loadSkills)
                                                .flatMap(skills -> membersFor(
                                                                tuple.getT2(), viewerUserId, viewerIsOrganiser)
                                                        .flatMap(members -> isMemberOf(tuple.getT2(), viewerUserId)
                                                                .map(isMember -> new TopicDetailView(
                                                                        topic,
                                                                        skills,
                                                                        tuple.getT1(),
                                                                        Optional.of(status),
                                                                        members,
                                                                        isAuthor(topic, viewerUserId),
                                                                        isMember)))))))
                        .switchIfEmpty(Mono.defer(() -> displayedNeededSkillIds(
                                        topicId, null, settings.getSkillDisplayMode())
                                .flatMap(this::loadSkills)
                                .map(skills -> new TopicDetailView(
                                        topic,
                                        skills,
                                        0,
                                        Optional.empty(),
                                        List.of(),
                                        isAuthor(topic, viewerUserId),
                                        false))))));
    }

    private Mono<Boolean> isMemberOf(List<UUID> activeMemberParticipantIds, UUID viewerUserId) {
        return participantService
                .findByUserId(viewerUserId)
                .map(participant -> activeMemberParticipantIds.contains(participant.getId()))
                .defaultIfEmpty(false);
    }

    // --- shared helpers -----------------------------------------------------------------------------

    private Mono<TopicAndGroup> withActiveGroupAndCount(Topic topic) {
        return groupService
                .findActiveGroupForTopic(topic.getId())
                .flatMap(group -> groupService
                        .activeMemberCount(group.getId())
                        .map(count -> new TopicAndGroup(topic, group, count)))
                .switchIfEmpty(Mono.just(new TopicAndGroup(topic, null, 0)));
    }

    /**
     * Splits {@code all} into the viewer's own Topics (pinned, fullest-first among themselves,
     * never truncated) and the existing fullness-sorted/not-full/Approved list (excluding any
     * already-pinned Topic, truncated so the combined total stays at {@code limit}) — FR-033,
     * research.md §11.
     */
    private List<PinnedTopicAndGroup> selectHomePageRows(
            List<TopicAndGroup> all, UUID viewerUserId, OrganiserSettings settings, int limit) {
        List<TopicAndGroup> own = all.stream()
                .filter(tg -> tg.topic().getCreatedByUserId().equals(viewerUserId))
                .sorted(Comparator.comparingInt(TopicAndGroup::memberCount).reversed())
                .toList();
        Set<UUID> ownIds = own.stream().map(tg -> tg.topic().getId()).collect(Collectors.toSet());
        List<TopicAndGroup> others = all.stream()
                .filter(tg -> tg.topic().getApprovalStatus() == TopicApprovalStatus.APPROVED)
                .filter(tg -> !ownIds.contains(tg.topic().getId()))
                .filter(tg -> tg.memberCount() < settings.getMaxGroupMembers())
                .sorted(Comparator.comparingInt(TopicAndGroup::memberCount).reversed())
                .toList();
        int remaining = Math.max(0, limit - own.size());
        List<TopicAndGroup> trimmedOthers = others.size() > remaining ? others.subList(0, remaining) : others;
        return Stream.concat(
                        own.stream().map(tg -> new PinnedTopicAndGroup(tg, true)),
                        trimmedOthers.stream().map(tg -> new PinnedTopicAndGroup(tg, false)))
                .toList();
    }

    /** Pins the viewer's own visible Topics above the rest, with no truncation (FR-034, research.md §11). */
    private List<PinnedTopic> pinOwnTopicsFirst(List<Topic> visible, UUID viewerUserId) {
        List<Topic> own = visible.stream()
                .filter(t -> t.getCreatedByUserId().equals(viewerUserId))
                .toList();
        Set<UUID> ownIds = own.stream().map(Topic::getId).collect(Collectors.toSet());
        List<Topic> others = visible.stream().filter(t -> !ownIds.contains(t.getId())).toList();
        return Stream.concat(
                        own.stream().map(t -> new PinnedTopic(t, true)),
                        others.stream().map(t -> new PinnedTopic(t, false)))
                .toList();
    }

    /**
     * A Topic is joinable (FR-035) only when it is Approved and either carries a compliance
     * override or its active Group's member count is still below the configured Maximum — the same
     * capacity/approval rule {@code TopicJoinService}/{@code GroupService.join} enforce server-side,
     * mirrored here purely for row display so a pinned Pending or full Topic never shows a "Join"
     * action even when the viewer is otherwise eligible.
     */
    private static boolean isJoinable(Topic topic, int memberCount, Group groupOrNull, OrganiserSettings settings) {
        if (topic.getApprovalStatus() != TopicApprovalStatus.APPROVED) {
            return false;
        }
        boolean override = groupOrNull != null && groupOrNull.isComplianceOverride();
        return override || memberCount < settings.getMaxGroupMembers();
    }

    private static boolean isAuthor(Topic topic, UUID viewerUserId) {
        return topic.getCreatedByUserId().equals(viewerUserId);
    }

    private Mono<OverviewRow> buildOverviewRow(Topic topic, OrganiserSettings settings, boolean pinned) {
        return authorDisplayName(topic.getCreatedByUserId())
                .flatMap(authorName -> groupService
                        .findActiveGroupForTopic(topic.getId())
                        .flatMap(group -> Mono.zip(
                                        groupService.activeMemberCount(group.getId()),
                                        groupService.activeMemberParticipantIds(group.getId()))
                                .flatMap(tuple -> complianceService
                                        .evaluate(group, tuple.getT2())
                                        .flatMap(status -> displayedNeededSkillIds(
                                                        topic.getId(), group, settings.getSkillDisplayMode())
                                                .flatMap(this::loadSkills)
                                                .map(skills -> new OverviewRow(
                                                        topic,
                                                        authorName,
                                                        tuple.getT1(),
                                                        skills,
                                                        Optional.of(status),
                                                        pinned,
                                                        isJoinable(topic, tuple.getT1(), group, settings))))))
                        .switchIfEmpty(Mono.defer(() -> displayedNeededSkillIds(
                                        topic.getId(), null, settings.getSkillDisplayMode())
                                .flatMap(this::loadSkills)
                                .map(skills -> new OverviewRow(
                                        topic,
                                        authorName,
                                        0,
                                        skills,
                                        Optional.empty(),
                                        pinned,
                                        isJoinable(topic, 0, null, settings))))));
    }

    private Mono<List<ParticipantService.ParticipantViewerDetail>> membersFor(
            List<UUID> memberParticipantIds, UUID viewerUserId, boolean viewerIsOrganiser) {
        return Flux.fromIterable(memberParticipantIds)
                .concatMap(participantId ->
                        participantService.findDetailForViewer(participantId, viewerUserId, viewerIsOrganiser))
                .collectList();
    }

    private Mono<String> authorDisplayName(UUID userId) {
        return userRepository.findById(userId).map(User::getDisplayName).defaultIfEmpty("Unknown user");
    }

    /**
     * A Topic's needed Skill ids after Skill Display Mode (FR-017, FR-018, Edge Cases):
     * {@code ALL_ASSOCIATED} — every needed Skill regardless of coverage; {@code
     * STILL_NEEDED_ONLY} — the needed set minus every Skill already held by a current active Group
     * member (a Topic with no Group yet, {@code groupOrNull == null}, treats every needed Skill as
     * still needed, since there is no coverage to subtract).
     */
    private Mono<List<UUID>> displayedNeededSkillIds(UUID topicId, Group groupOrNull, SkillDisplayMode mode) {
        return topicSkillIds(topicId).flatMap(neededIds -> {
            if (mode == SkillDisplayMode.ALL_ASSOCIATED || groupOrNull == null || neededIds.isEmpty()) {
                return Mono.just(neededIds);
            }
            return groupService
                    .activeMemberParticipantIds(groupOrNull.getId())
                    .flatMap(this::coveredSkillIds)
                    .map(covered ->
                            neededIds.stream().filter(id -> !covered.contains(id)).toList());
        });
    }

    private Mono<Set<UUID>> coveredSkillIds(List<UUID> memberParticipantIds) {
        if (memberParticipantIds.isEmpty()) {
            return Mono.just(Set.of());
        }
        return Flux.fromIterable(memberParticipantIds)
                .concatMap(this::participantSkillIds)
                .flatMap(Flux::fromIterable)
                .collect(Collectors.toSet());
    }

    private Mono<List<UUID>> viewerOfferedSkillIds(UUID viewerParticipantIdOrNull, List<UUID> displayedIds) {
        if (viewerParticipantIdOrNull == null || displayedIds.isEmpty()) {
            return Mono.just(List.of());
        }
        return participantSkillIds(viewerParticipantIdOrNull)
                .map(viewerSkillIds -> displayedIds.stream()
                        .filter(viewerSkillIds::contains)
                        .toList());
    }

    private Mono<List<UUID>> topicSkillIds(UUID topicId) {
        return databaseClient
                .sql("SELECT skill_id FROM topic_skills WHERE topic_id = :tid")
                .bind("tid", topicId)
                .mapValue(UUID.class)
                .all()
                .collectList();
    }

    private Mono<List<UUID>> participantSkillIds(UUID participantId) {
        return databaseClient
                .sql("SELECT skill_id FROM participant_skills WHERE participant_id = :pid")
                .bind("pid", participantId)
                .mapValue(UUID.class)
                .all()
                .collectList();
    }

    private Mono<List<Skill>> loadSkills(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Mono.just(List.of());
        }
        return skillRepository.findAllById(ids).collectList();
    }

    // --- private assembly types ----------------------------------------------------------------

    private record TopicAndGroup(Topic topic, Group group, int memberCount) {}

    private record PinnedTopicAndGroup(TopicAndGroup tg, boolean pinned) {}

    private record PinnedTopic(Topic topic, boolean pinned) {}

    // --- read-model view types -------------------------------------------------------------------

    /** One Home Page row (FR-004, FR-033, FR-035). */
    public record OpenTopicRow(
            Topic topic, int memberCount, List<Skill> viewerOfferedSkills, boolean pinned, boolean joinable) {}

    /**
     * One Topic Overview row (FR-006, FR-034, FR-035); an empty {@code complianceStatus} renders as
     * a blank cell (FR-014a).
     */
    public record OverviewRow(
            Topic topic,
            String authorDisplayName,
            int memberCount,
            List<Skill> neededSkills,
            Optional<ComplianceStatus> complianceStatus,
            boolean pinned,
            boolean joinable) {}

    /**
     * The Topic Details view's read model (FR-030, FR-031, FR-032); an empty {@code
     * complianceStatus} renders as a blank cell (FR-014a), same convention as {@link OverviewRow}.
     */
    public record TopicDetailView(
            Topic topic,
            List<Skill> neededSkills,
            int memberCount,
            Optional<ComplianceStatus> complianceStatus,
            List<ParticipantService.ParticipantViewerDetail> members,
            boolean author,
            boolean isMember) {}
}

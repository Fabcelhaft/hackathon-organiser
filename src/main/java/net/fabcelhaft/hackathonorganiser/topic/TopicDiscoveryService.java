package net.fabcelhaft.hackathonorganiser.topic;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
    private final TopicUpvoteService topicUpvoteService;

    public TopicDiscoveryService(
            TopicRepository topicRepository,
            GroupService groupService,
            OrganiserSettingsService organiserSettingsService,
            ComplianceService complianceService,
            SkillRepository skillRepository,
            UserRepository userRepository,
            ParticipantService participantService,
            DatabaseClient databaseClient,
            TopicUpvoteService topicUpvoteService) {
        this.topicRepository = topicRepository;
        this.groupService = groupService;
        this.organiserSettingsService = organiserSettingsService;
        this.complianceService = complianceService;
        this.skillRepository = skillRepository;
        this.userRepository = userRepository;
        this.participantService = participantService;
        this.databaseClient = databaseClient;
        this.topicUpvoteService = topicUpvoteService;
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
    public Flux<OpenTopicRow> findOpenTopicsForHomePage(UUID viewerUserId, int limit) {
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
                        .flatMapMany(all -> loadUpvoteData(topicIdsOf(all), viewerUserId, settings)
                                .flatMapMany(upvoteData -> Flux.fromIterable(
                                                selectHomePageRows(all, viewerUserId, settings, limit, upvoteData.counts()))
                                        // Feature 013 (FR-015a): this used to be a concatMap running
                                        // displayedNeededSkillIds -> viewerOfferedSkillIds -> loadSkills
                                        // per row, purely to fill the Home Page's "Your Skills" column.
                                        // That column is gone, so the three per-row database round trips
                                        // go with it and the chain collapses to a plain map (SC-011).
                                        .map(selection -> new OpenTopicRow(
                                                selection.tg().topic(),
                                                selection.tg().memberCount(),
                                                selection.pinned(),
                                                isJoinable(selection.tg().topic(), selection.tg().memberCount(),
                                                        selection.tg().group(), settings),
                                                upvoteData.countFor(selection.tg().topic().getId()),
                                                upvoteData.viewerHasUpvoted(
                                                        selection.tg().topic().getId()))))));
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
                        .flatMapMany(visible -> loadUpvoteData(
                                        visible.stream().map(Topic::getId).collect(Collectors.toSet()),
                                        viewerUserId,
                                        settings)
                                .flatMapMany(upvoteData -> Flux.fromIterable(pinOwnTopicsFirst(visible, viewerUserId))
                                        .concatMap(p -> buildOverviewRow(p.topic(), settings, p.pinned(), upvoteData)))));
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
                .flatMap(topic -> Mono.zip(
                                organiserSettingsService.current(), authorDisplayName(topic.getCreatedByUserId()))
                        .flatMap(settingsAndAuthor -> {
                            OrganiserSettings settings = settingsAndAuthor.getT1();
                            String authorName = settingsAndAuthor.getT2();
                            return loadUpvoteData(Set.of(topicId), viewerUserId, settings)
                                    .flatMap(upvoteData -> groupService
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
                                                                            .flatMap(members -> isMemberOf(
                                                                                            tuple.getT2(), viewerUserId)
                                                                                    .map(isMember -> new TopicDetailView(
                                                                                            topic,
                                                                                            authorName,
                                                                                            skills,
                                                                                            tuple.getT1(),
                                                                                            Optional.of(status),
                                                                                            members,
                                                                                            isAuthor(topic, viewerUserId),
                                                                                            isMember,
                                                                                            upvoteData.countFor(topicId),
                                                                                            upvoteData.viewerHasUpvoted(
                                                                                                    topicId))))))))
                                            .switchIfEmpty(Mono.defer(() -> displayedNeededSkillIds(
                                                            topicId, null, settings.getSkillDisplayMode())
                                                    .flatMap(this::loadSkills)
                                                    .map(skills -> new TopicDetailView(
                                                            topic,
                                                            authorName,
                                                            skills,
                                                            0,
                                                            Optional.empty(),
                                                            List.of(),
                                                            isAuthor(topic, viewerUserId),
                                                            false,
                                                            upvoteData.countFor(topicId),
                                                            upvoteData.viewerHasUpvoted(topicId))))));
                        }));
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
            List<TopicAndGroup> all,
            UUID viewerUserId,
            OrganiserSettings settings,
            int limit,
            Map<UUID, Integer> upvoteCounts) {
        Comparator<TopicAndGroup> comparator =
                fullnessComparator(upvoteCounts, settings.isTopicUpvotingEnabled());
        List<TopicAndGroup> own = all.stream()
                .filter(tg -> tg.topic().getCreatedByUserId().equals(viewerUserId))
                .sorted(comparator)
                .toList();
        Set<UUID> ownIds = own.stream().map(tg -> tg.topic().getId()).collect(Collectors.toSet());
        List<TopicAndGroup> others = all.stream()
                .filter(tg -> tg.topic().getApprovalStatus() == TopicApprovalStatus.APPROVED)
                .filter(tg -> !ownIds.contains(tg.topic().getId()))
                .filter(tg -> tg.memberCount() < settings.getMaxGroupMembers())
                .sorted(comparator)
                .toList();
        int remaining = Math.max(0, limit - own.size());
        List<TopicAndGroup> trimmedOthers = others.size() > remaining ? others.subList(0, remaining) : others;
        return Stream.concat(
                        own.stream().map(tg -> new PinnedTopicAndGroup(tg, true)),
                        trimmedOthers.stream().map(tg -> new PinnedTopicAndGroup(tg, false)))
                .toList();
    }

    /**
     * The Home Page's fullness-first ordering (feature 005), extended with an upvote-count
     * tiebreak (FR-005a, research.md §6): only while {@code upvotingEnabled} — when it is
     * {@code false}, {@code upvoteCounts} is never even consulted, so a disabled feature has zero
     * influence on ordering.
     */
    private static Comparator<TopicAndGroup> fullnessComparator(
            Map<UUID, Integer> upvoteCounts, boolean upvotingEnabled) {
        Comparator<TopicAndGroup> byMemberCount =
                Comparator.comparingInt(TopicAndGroup::memberCount).reversed();
        if (!upvotingEnabled) {
            return byMemberCount;
        }
        return byMemberCount.thenComparing(
                (TopicAndGroup tg) -> upvoteCounts.getOrDefault(tg.topic().getId(), 0),
                Comparator.reverseOrder());
    }

    private static Set<UUID> topicIdsOf(List<TopicAndGroup> all) {
        return all.stream().map(tg -> tg.topic().getId()).collect(Collectors.toSet());
    }

    /**
     * Bulk-loads upvote counts and the viewer's own upvoted-id subset for the given Topics — but
     * only while the upvoting feature is enabled (FR-007): when disabled, {@link
     * TopicUpvoteService} is never called at all, and every row's count/viewer-state is {@code 0}/
     * {@code false} (data-model.md "Read-Model Extensions").
     */
    private Mono<UpvoteData> loadUpvoteData(Set<UUID> topicIds, UUID viewerUserId, OrganiserSettings settings) {
        if (!settings.isTopicUpvotingEnabled()) {
            return Mono.just(UpvoteData.EMPTY);
        }
        return Mono.zip(
                        topicUpvoteService.countsFor(topicIds),
                        topicUpvoteService.viewerUpvotedTopicIds(topicIds, viewerUserId))
                .map(tuple -> new UpvoteData(tuple.getT1(), tuple.getT2()));
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

    private Mono<OverviewRow> buildOverviewRow(
            Topic topic, OrganiserSettings settings, boolean pinned, UpvoteData upvoteData) {
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
                                                        isJoinable(topic, tuple.getT1(), group, settings),
                                                        upvoteData.countFor(topic.getId()),
                                                        upvoteData.viewerHasUpvoted(topic.getId()))))))
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
                                        isJoinable(topic, 0, null, settings),
                                        upvoteData.countFor(topic.getId()),
                                        upvoteData.viewerHasUpvoted(topic.getId()))))));
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

    /**
     * Bulk upvote read data for a set of Topics (US1, FR-004, FR-005): counts keyed by Topic id
     * (absent means zero) and the subset the viewer currently has an active upvote on. {@link
     * #EMPTY} is used whenever the upvoting feature is disabled, so no row ever needs a null check.
     */
    private record UpvoteData(Map<UUID, Integer> counts, Set<UUID> viewerUpvotedIds) {
        private static final UpvoteData EMPTY = new UpvoteData(Map.of(), Set.of());

        int countFor(UUID topicId) {
            return counts.getOrDefault(topicId, 0);
        }

        boolean viewerHasUpvoted(UUID topicId) {
            return viewerUpvotedIds.contains(topicId);
        }
    }

    // --- read-model view types -------------------------------------------------------------------

    /**
     * One Home Page row (FR-004, FR-033, FR-035, FR-005a).
     *
     * <p>Feature 013 (FR-015a) removed {@code viewerOfferedSkills}: the Home Page no longer shows a
     * skills column, so the value had no reader. The Topic Overview's {@link OverviewRow} keeps its
     * own {@code neededSkills}, which is a different thing — a property of the Topic rather than an
     * intersection with the viewer.
     */
    public record OpenTopicRow(
            Topic topic,
            int memberCount,
            boolean pinned,
            boolean joinable,
            int upvoteCount,
            boolean viewerHasUpvoted) {}

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
            boolean joinable,
            int upvoteCount,
            boolean viewerHasUpvoted) {}

    /**
     * The Topic Details view's read model (FR-030, FR-031, FR-032, FR-014); an empty {@code
     * complianceStatus} renders as a blank cell (FR-014a), same convention as {@link OverviewRow}.
     * {@code authorDisplayName} (FR-014) is a field this view lacked before feature 012 — the
     * Topic Overview's {@link OverviewRow} already carried the equivalent since feature 005.
     */
    public record TopicDetailView(
            Topic topic,
            String authorDisplayName,
            List<Skill> neededSkills,
            int memberCount,
            Optional<ComplianceStatus> complianceStatus,
            List<ParticipantService.ParticipantViewerDetail> members,
            boolean author,
            boolean isMember,
            int upvoteCount,
            boolean viewerHasUpvoted) {}
}

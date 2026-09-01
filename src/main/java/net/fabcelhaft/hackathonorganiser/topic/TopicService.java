package net.fabcelhaft.hackathonorganiser.topic;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.skill.Skill;
import net.fabcelhaft.hackathonorganiser.skill.SkillRepository;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Organiser-facing operations on {@link Topic} records (T048): create/edit with required fields
 * (FR-015 — rejecting an unknown {@code created_by_user_id} on create; the creator is immutable
 * after creation, so {@link #update} has no parameter for it at all), and Skill association
 * replace via {@link DatabaseClient} against {@code topic_skills} (FR-010).
 *
 * <p>{@code topic_skills} is a composite-key "pure association" table that a single-column-
 * {@code @Id} {@link org.springframework.data.repository.reactive.ReactiveCrudRepository} cannot
 * back (research.md §4), so this service manipulates it directly via {@link DatabaseClient} —
 * the same approach {@code participant_skills} uses in {@code ParticipantService} for the same
 * reason.
 */
@Service
public class TopicService {

    private final TopicRepository topicRepository;
    private final UserRepository userRepository;
    private final SkillRepository skillRepository;
    private final DatabaseClient databaseClient;
    private final OrganiserSettingsService organiserSettingsService;

    public TopicService(
            TopicRepository topicRepository,
            UserRepository userRepository,
            SkillRepository skillRepository,
            DatabaseClient databaseClient,
            OrganiserSettingsService organiserSettingsService) {
        this.topicRepository = topicRepository;
        this.userRepository = userRepository;
        this.skillRepository = skillRepository;
        this.databaseClient = databaseClient;
        this.organiserSettingsService = organiserSettingsService;
    }

    public Flux<Topic> findAll() {
        return topicRepository.findAll();
    }

    /** A single Topic by id — used by the homepage to show a Participant's assigned Topic. */
    public Mono<Topic> findById(UUID id) {
        return topicRepository.findById(id);
    }

    /** The pool the new-Topic form's creator dropdown picks from. */
    public Flux<User> allUsers() {
        return userRepository.findAll();
    }

    public Flux<Skill> allSkills() {
        return skillRepository.findAll();
    }

    /**
     * Creates a new Topic, rejecting a missing {@code name}/{@code description}/
     * {@code createdByUserId} or an unknown {@code createdByUserId} (FR-015) with a friendly
     * {@link TopicConflictException}, then replaces its Skill association set (FR-010).
     */
    public Mono<Topic> create(String name, String description, UUID createdByUserId, List<UUID> skillIds) {
        List<UUID> ids = distinct(skillIds);
        if (isBlank(name) || isBlank(description) || createdByUserId == null) {
            return Mono.error(
                    new TopicConflictException("name, description, and created_by_user_id are required"));
        }
        return userRepository.existsById(createdByUserId).flatMap(creatorExists -> {
            if (!creatorExists) {
                return Mono.error(new TopicConflictException("Unknown user: " + createdByUserId));
            }
            return allSkillIdsExist(ids).flatMap(allExist -> {
                if (!allExist) {
                    return Mono.error(
                            new TopicConflictException("One or more selected skills do not exist"));
                }
                return topicRepository
                        .save(newTopic(name, description, createdByUserId))
                        .flatMap(topic -> replaceTopicSkills(topic.getId(), ids).thenReturn(topic));
            });
        });
    }

    /**
     * Updates a Topic's {@code name}, {@code description}, and Skill selections (FR-010). The
     * creator ({@code created_by_user_id}) is immutable after creation (FR-015): this method has
     * no parameter for it at all, so there is no code path by which this route could ever
     * reassign it. Completes empty if no Topic exists with the given id.
     */
    public Mono<Topic> update(UUID id, String name, String description, List<UUID> skillIds) {
        List<UUID> ids = distinct(skillIds);
        if (isBlank(name) || isBlank(description)) {
            return Mono.error(new TopicConflictException("name and description are required"));
        }
        return topicRepository.findById(id).flatMap(topic -> allSkillIdsExist(ids).flatMap(allExist -> {
            if (!allExist) {
                return Mono.error(new TopicConflictException("One or more selected skills do not exist"));
            }
            topic.setName(name);
            topic.setDescription(description);
            topic.setUpdatedAt(Instant.now());
            return topicRepository
                    .save(topic)
                    .flatMap(saved -> replaceTopicSkills(id, ids).thenReturn(saved));
        }));
    }

    // --- Self-service propose/edit (FR-009-FR-013, FR-016, research.md §6) -----------------------

    /**
     * Creates a Topic authored by a Participant with its Skill selections (FR-001, FR-010),
     * rejecting a blank {@code name}/{@code description} with a {@link TopicConflictException}
     * (FR-037, matching {@link #create}'s existing validation pattern) or an unknown {@code
     * skillIds} entry the same way {@link #create} already does (research.md §6). {@code
     * approvalStatus} is set once, at creation, from the current {@code
     * OrganiserSettings.topicApprovalRequired} value (FR-013) — never re-derived later, so
     * disabling that setting afterward is not retroactive (FR-016).
     */
    public Mono<Topic> propose(UUID authorUserId, String name, String description, List<UUID> skillIds) {
        List<UUID> ids = distinct(skillIds);
        if (isBlank(name) || isBlank(description)) {
            return Mono.error(new TopicConflictException("name and description are required"));
        }
        return allSkillIdsExist(ids).flatMap(allExist -> {
            if (!allExist) {
                return Mono.error(new TopicConflictException("One or more selected skills do not exist"));
            }
            return organiserSettingsService.current().flatMap(settings -> {
                Topic topic = new Topic();
                topic.setName(name);
                topic.setDescription(description);
                topic.setCreatedByUserId(authorUserId);
                topic.setApprovalStatus(
                        settings.isTopicApprovalRequired()
                                ? TopicApprovalStatus.PENDING
                                : TopicApprovalStatus.APPROVED);
                Instant now = Instant.now();
                topic.setCreatedAt(now);
                topic.setUpdatedAt(now);
                return topicRepository
                        .save(topic)
                        .flatMap(saved -> replaceTopicSkills(saved.getId(), ids).thenReturn(saved));
            });
        });
    }

    /**
     * A single Topic, honoring FR-012a's Pending-visibility rule: completes empty if {@code id} is
     * unknown, or the Topic is {@code PENDING} and {@code viewerUserId} is neither its author nor
     * {@code viewerIsOrganiser} — enforced here, at the read-model layer, so no route can
     * accidentally leak a Pending Topic regardless of which controller calls this.
     */
    public Mono<Topic> findVisibleTo(UUID id, UUID viewerUserId, boolean viewerIsOrganiser) {
        return topicRepository.findById(id).filter(topic -> isVisibleTo(topic, viewerUserId, viewerIsOrganiser));
    }

    /**
     * Author-only self-service update of {@code name}/{@code description}/Skill selections
     * (FR-002, FR-011); does not re-trigger approval (spec Assumptions) — {@code approvalStatus} is
     * left untouched. Rejects a non-author with a {@link TopicConflictException} (callers are
     * expected to have already translated the full 404/403 rule via {@link #findVisibleTo} plus
     * their own authorship check — this is a defensive second check, not the primary authorization
     * gate), or an unknown {@code skillIds} entry the same way {@link #update} already does
     * (research.md §6). Completes empty if no Topic exists with the given id.
     */
    public Mono<Topic> updateAsAuthor(
            UUID id, UUID requesterUserId, String name, String description, List<UUID> skillIds) {
        List<UUID> ids = distinct(skillIds);
        if (isBlank(name) || isBlank(description)) {
            return Mono.error(new TopicConflictException("name and description are required"));
        }
        return topicRepository.findById(id).flatMap(topic -> {
            if (!topic.getCreatedByUserId().equals(requesterUserId)) {
                return Mono.error(new TopicConflictException("You may only edit your own Topic"));
            }
            return allSkillIdsExist(ids).flatMap(allExist -> {
                if (!allExist) {
                    return Mono.error(new TopicConflictException("One or more selected skills do not exist"));
                }
                topic.setName(name);
                topic.setDescription(description);
                topic.setUpdatedAt(Instant.now());
                return topicRepository
                        .save(topic)
                        .flatMap(saved -> replaceTopicSkills(id, ids).thenReturn(saved));
            });
        });
    }

    /**
     * The viewer-scoped, 3-group visibility/ordering read model for the homepage's topic list
     * (FR-009a, research.md §6): (1) the viewer's own {@code PENDING} Topics, (2) the viewer's own
     * {@code APPROVED} Topics, (3) every other Topic visible to the viewer (other authors'
     * {@code APPROVED} Topics always; other authors' {@code PENDING} Topics too, but only when
     * {@code viewerIsOrganiser} — FR-012a) — each group ordered by creation date. A Topic appears
     * in exactly one group.
     */
    public Mono<TopicListView> findVisibleTopicsFor(UUID viewerUserId, boolean viewerIsOrganiser) {
        return topicRepository
                .findAll()
                .filter(topic -> isVisibleTo(topic, viewerUserId, viewerIsOrganiser))
                .collectList()
                .map(topics -> {
                    Comparator<Topic> byCreatedAt = Comparator.comparing(Topic::getCreatedAt);
                    List<Topic> ownPending = topics.stream()
                            .filter(t -> isOwn(t, viewerUserId) && t.getApprovalStatus() == TopicApprovalStatus.PENDING)
                            .sorted(byCreatedAt)
                            .toList();
                    List<Topic> ownApproved = topics.stream()
                            .filter(t ->
                                    isOwn(t, viewerUserId) && t.getApprovalStatus() == TopicApprovalStatus.APPROVED)
                            .sorted(byCreatedAt)
                            .toList();
                    List<Topic> others = topics.stream()
                            .filter(t -> !isOwn(t, viewerUserId))
                            .sorted(byCreatedAt)
                            .toList();
                    return new TopicListView(ownPending, ownApproved, others);
                });
    }

    /**
     * The distinct authors ({@code createdByUserId}) of the given Topics, keyed by id — used by
     * the homepage to show each Topic's author display name and OIDC subject (FR-009) without a
     * per-row lookup.
     */
    public Mono<Map<UUID, User>> loadAuthors(List<Topic> topics) {
        Set<UUID> authorIds =
                topics.stream().map(Topic::getCreatedByUserId).collect(Collectors.toSet());
        if (authorIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        return userRepository.findAllById(authorIds).collectMap(User::getId, user -> user);
    }

    /** Organiser-only: moves a Pending Topic to Approved (FR-014); a no-op if already Approved. */
    public Mono<Topic> approve(UUID topicId) {
        return topicRepository.findById(topicId).flatMap(topic -> {
            if (topic.getApprovalStatus() == TopicApprovalStatus.APPROVED) {
                return Mono.just(topic);
            }
            topic.setApprovalStatus(TopicApprovalStatus.APPROVED);
            topic.setUpdatedAt(Instant.now());
            return topicRepository.save(topic);
        });
    }

    /**
     * Organiser-only: reassigns a Topic's author (FR-015, superseding 002's immutability
     * guarantee — see {@link Topic}'s class comment). Rejects an unknown {@code newAuthorUserId}
     * with a {@link TopicConflictException} — the exact pattern {@link #create} already uses for
     * the identical "unknown user id" check, so the controller can re-render the edit form with a
     * field-associated error (FR-037) instead of a bare 404. Completes empty if {@code topicId} is
     * unknown.
     */
    public Mono<Topic> reassignAuthor(UUID topicId, UUID newAuthorUserId) {
        return topicRepository.findById(topicId).flatMap(topic -> userRepository
                .existsById(newAuthorUserId)
                .flatMap(exists -> {
                    if (!exists) {
                        return Mono.error(new TopicConflictException("Unknown user: " + newAuthorUserId));
                    }
                    topic.setCreatedByUserId(newAuthorUserId);
                    topic.setUpdatedAt(Instant.now());
                    return topicRepository.save(topic);
                }));
    }

    /** Package-visible (not {@code private}) so {@link TopicDiscoveryService} can reuse this exact rule (research.md §7). */
    static boolean isVisibleTo(Topic topic, UUID viewerUserId, boolean viewerIsOrganiser) {
        return topic.getApprovalStatus() != TopicApprovalStatus.PENDING
                || viewerIsOrganiser
                || isOwn(topic, viewerUserId);
    }

    private static boolean isOwn(Topic topic, UUID viewerUserId) {
        return topic.getCreatedByUserId().equals(viewerUserId);
    }

    /** The homepage's viewer-scoped topic-list read model (FR-009a) — see {@link #findVisibleTopicsFor}. */
    public record TopicListView(List<Topic> ownPending, List<Topic> ownApproved, List<Topic> others) {}

    /**
     * A Topic's detail-view read model: the Topic itself, its creator's stored {@code
     * display_name} (retained even if that User's access is later revoked — edge case, spec.md),
     * and its currently associated Skills. Completes empty if no Topic exists with the given id.
     */
    public Mono<TopicDetail> findDetail(UUID id) {
        return topicRepository.findById(id).flatMap(topic -> userRepository
                .findById(topic.getCreatedByUserId())
                .map(User::getDisplayName)
                .defaultIfEmpty("Unknown user")
                .flatMap(creatorDisplayName -> loadSkills(id)
                        .map(skills -> new TopicDetail(
                                topic,
                                creatorDisplayName,
                                skills,
                                skills.stream().map(Skill::getId).toList()))));
    }

    private Mono<List<Skill>> loadSkills(UUID topicId) {
        return databaseClient
                .sql("SELECT skill_id FROM topic_skills WHERE topic_id = :tid")
                .bind("tid", topicId)
                .mapValue(UUID.class)
                .all()
                .collectList()
                .flatMap(ids -> ids.isEmpty()
                        ? Mono.just(List.<Skill>of())
                        : skillRepository.findAllById(ids).collectList());
    }

    private Mono<Boolean> allSkillIdsExist(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Mono.just(true);
        }
        Set<UUID> requested = new HashSet<>(ids);
        return skillRepository
                .findAllById(requested)
                .map(Skill::getId)
                .collect(Collectors.toSet())
                .map(found -> found.size() == requested.size());
    }

    private Mono<Void> replaceTopicSkills(UUID topicId, List<UUID> skillIds) {
        return databaseClient
                .sql("DELETE FROM topic_skills WHERE topic_id = :tid")
                .bind("tid", topicId)
                .then()
                .thenMany(Flux.fromIterable(skillIds))
                .concatMap(skillId -> databaseClient
                        .sql("INSERT INTO topic_skills (topic_id, skill_id) VALUES (:tid, :sid)")
                        .bind("tid", topicId)
                        .bind("sid", skillId)
                        .then())
                .then();
    }

    private static List<UUID> distinct(List<UUID> ids) {
        return ids == null ? List.of() : ids.stream().distinct().toList();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private Topic newTopic(String name, String description, UUID createdByUserId) {
        Topic topic = new Topic();
        topic.setName(name);
        topic.setDescription(description);
        topic.setCreatedByUserId(createdByUserId);
        // An Organiser-created Topic (this method) never goes through the approval workflow —
        // that only applies to Participant self-service proposals (FR-013, propose(...) below).
        topic.setApprovalStatus(TopicApprovalStatus.APPROVED);
        Instant now = Instant.now();
        topic.setCreatedAt(now);
        topic.setUpdatedAt(now);
        return topic;
    }

    // --- Read-model view type -------------------------------------------------------------------

    public record TopicDetail(
            Topic topic, String creatorDisplayName, List<Skill> skills, List<UUID> skillIds) {}
}

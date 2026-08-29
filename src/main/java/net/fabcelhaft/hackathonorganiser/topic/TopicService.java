package net.fabcelhaft.hackathonorganiser.topic;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

    public TopicService(
            TopicRepository topicRepository,
            UserRepository userRepository,
            SkillRepository skillRepository,
            DatabaseClient databaseClient) {
        this.topicRepository = topicRepository;
        this.userRepository = userRepository;
        this.skillRepository = skillRepository;
        this.databaseClient = databaseClient;
    }

    public Flux<Topic> findAll() {
        return topicRepository.findAll();
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
        Instant now = Instant.now();
        topic.setCreatedAt(now);
        topic.setUpdatedAt(now);
        return topic;
    }

    // --- Read-model view type -------------------------------------------------------------------

    public record TopicDetail(
            Topic topic, String creatorDisplayName, List<Skill> skills, List<UUID> skillIds) {}
}

package net.fabcelhaft.hackathonorganiser.topic;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Casts, withdraws, and counts anonymous per-user upvotes on a Topic (spec.md Key Entities: Topic
 * Upvote; data-model.md "New Entity: Topic Upvote"; FR-001-FR-004).
 *
 * <p>{@code topic_upvotes} is a composite-key "pure association" table — no independent UUID, no
 * soft-delete flag — that a single-column-{@code @Id} {@link
 * org.springframework.data.repository.reactive.ReactiveCrudRepository} cannot back, so this
 * service manipulates it directly via {@link DatabaseClient}, the same approach {@code
 * topic_skills}/{@code participant_skills} use elsewhere in this codebase for the same reason. No
 * entity class exists for it, matching those tables' precedent.
 *
 * <p><b>Concurrency (research.md §3):</b> {@link #upvote} pre-checks existence and skips the
 * insert when the caller already has an active upvote; the composite primary key on {@code
 * (topic_id, user_id)} is the structural backstop for the rare lost race (two concurrent casts
 * both passing the pre-check), caught and treated as the same success — mirroring {@code
 * GroupService}'s existing pre-check-plus-constraint-backstop convention, except both paths here
 * resolve to plain success: an upvote is idempotent by definition (FR-001), there is nothing to
 * reject.
 *
 * <p><b>Anonymity (FR-003, research.md §1):</b> {@code user_id} is never joined out to a display
 * name or listed anywhere by this class — only aggregate counts ({@link #countsFor}) and "does
 * this viewer have a row" checks ({@link #viewerUpvotedTopicIds}) ever read it. No {@code
 * AuditEntry} is ever recorded for an upvote/withdraw action; that omission is deliberate, not an
 * oversight.
 */
@Service
public class TopicUpvoteService {

    private final DatabaseClient databaseClient;

    public TopicUpvoteService(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /** Casts an upvote; idempotent if the user already has an active one on this Topic (FR-001). */
    public Mono<Void> upvote(UUID topicId, UUID userId) {
        return existsUpvote(topicId, userId)
                .flatMap(exists -> exists ? Mono.<Void>empty() : insertUpvote(topicId, userId));
    }

    /** Withdraws an upvote; a no-op (not an error) if the user had none (FR-002). */
    public Mono<Void> withdraw(UUID topicId, UUID userId) {
        return databaseClient
                .sql("DELETE FROM topic_upvotes WHERE topic_id = :tid AND user_id = :uid")
                .bind("tid", topicId)
                .bind("uid", userId)
                .then();
    }

    /**
     * The active upvote count for each of the given Topics, keyed by id — a Topic with zero
     * upvotes is simply absent from the returned map (callers default to {@code 0}).
     */
    public Mono<Map<UUID, Integer>> countsFor(Set<UUID> topicIds) {
        if (topicIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        UUID[] ids = topicIds.toArray(new UUID[0]);
        return databaseClient
                .sql("SELECT topic_id, COUNT(*) AS upvote_count FROM topic_upvotes"
                        + " WHERE topic_id = ANY(:tids) GROUP BY topic_id")
                .bind("tids", ids)
                .map((row, metadata) -> Map.entry(
                        row.get("topic_id", UUID.class),
                        row.get("upvote_count", Long.class).intValue()))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    /** Which of the given Topics the viewer currently has an active upvote on (FR-004). */
    public Mono<Set<UUID>> viewerUpvotedTopicIds(Set<UUID> topicIds, UUID viewerUserId) {
        if (topicIds.isEmpty()) {
            return Mono.just(Set.of());
        }
        UUID[] ids = topicIds.toArray(new UUID[0]);
        return databaseClient
                .sql("SELECT topic_id FROM topic_upvotes WHERE topic_id = ANY(:tids) AND user_id = :uid")
                .bind("tids", ids)
                .bind("uid", viewerUserId)
                .mapValue(UUID.class)
                .all()
                .collect(Collectors.toSet());
    }

    private Mono<Void> insertUpvote(UUID topicId, UUID userId) {
        return databaseClient
                .sql("INSERT INTO topic_upvotes (topic_id, user_id) VALUES (:tid, :uid)")
                .bind("tid", topicId)
                .bind("uid", userId)
                .then()
                .onErrorResume(DataIntegrityViolationException.class, ex -> Mono.empty());
    }

    private Mono<Boolean> existsUpvote(UUID topicId, UUID userId) {
        return databaseClient
                .sql("SELECT EXISTS(SELECT 1 FROM topic_upvotes WHERE topic_id = :tid AND user_id = :uid)")
                .bind("tid", topicId)
                .bind("uid", userId)
                .mapValue(Boolean.class)
                .one();
    }
}

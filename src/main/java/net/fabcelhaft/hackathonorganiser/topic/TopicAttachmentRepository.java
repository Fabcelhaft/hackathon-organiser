package net.fabcelhaft.hackathonorganiser.topic;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

/**
 * Reactive repository for {@link TopicAttachment} (010 data-model.md).
 *
 * <p>Read methods here load the full row including its {@code bytea} payload, so they are used
 * only on the download path. Listing goes through {@link TopicAttachmentService#listFor}, which
 * selects metadata columns only.
 */
public interface TopicAttachmentRepository extends ReactiveCrudRepository<TopicAttachment, UUID> {

    /**
     * The attachment with this id <em>belonging to this Topic</em>. Matching on the pair is what
     * stops one Topic's visible URL being used to fetch another Topic's attachment by id
     * (FR-020); completes empty when the id is unknown or belongs elsewhere.
     */
    Mono<TopicAttachment> findByIdAndTopicId(UUID id, UUID topicId);

    /** How many attachments the Topic already holds, for the per-Topic cap (FR-016). */
    Mono<Long> countByTopicId(UUID topicId);
}

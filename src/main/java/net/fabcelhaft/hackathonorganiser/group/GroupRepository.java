package net.fabcelhaft.hackathonorganiser.group;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

/**
 * Reactive repository for {@link Group} (T055).
 */
public interface GroupRepository extends ReactiveCrudRepository<Group, UUID> {

    /**
     * Looks up the Group for a Topic in a given status — backs the "at most one active Group per
     * Topic" check (FR-016a) and the read-side "does this Topic currently have an active Group"
     * question (contracts/topic-management.md).
     */
    Mono<Group> findByTopicIdAndStatus(UUID topicId, GroupStatus status);
}

package net.fabcelhaft.hackathonorganiser.participant;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

/**
 * Reactive repository for {@link Participant} (T039).
 */
public interface ParticipantRepository extends ReactiveCrudRepository<Participant, UUID> {

    /**
     * Looks up a Participant by the User it belongs to — backs FR-006a's "at most one Participant
     * per User" check.
     */
    Mono<Participant> findByUserId(UUID userId);
}

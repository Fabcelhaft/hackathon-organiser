package net.fabcelhaft.hackathonorganiser.event;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

/** Basic CRUD access to {@link EventDestination} (data-model.md "Event Destination"). */
public interface EventDestinationRepository extends ReactiveCrudRepository<EventDestination, UUID> {

    Mono<EventDestination> findByName(String name);
}

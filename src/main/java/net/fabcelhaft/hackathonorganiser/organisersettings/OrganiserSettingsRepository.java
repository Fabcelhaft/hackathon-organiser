package net.fabcelhaft.hackathonorganiser.organisersettings;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

/**
 * Reactive repository for the {@link OrganiserSettings} singleton row (T006).
 */
public interface OrganiserSettingsRepository extends ReactiveCrudRepository<OrganiserSettings, UUID> {

    /** The one and only row — seeded at startup by schema.sql (research.md §4). */
    Mono<OrganiserSettings> findBySingletonTrue();
}

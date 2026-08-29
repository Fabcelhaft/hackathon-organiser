package net.fabcelhaft.hackathonorganiser.customfield;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive repository for {@link CustomFieldOption} (T027).
 */
public interface CustomFieldOptionRepository extends ReactiveCrudRepository<CustomFieldOption, UUID> {

    Flux<CustomFieldOption> findByCustomFieldDefinitionId(UUID customFieldDefinitionId);

    Mono<Boolean> existsByCustomFieldDefinitionIdAndLabelIgnoreCase(UUID customFieldDefinitionId, String label);
}

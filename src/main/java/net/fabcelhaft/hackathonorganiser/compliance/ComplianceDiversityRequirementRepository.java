package net.fabcelhaft.hackathonorganiser.compliance;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

/**
 * Reactive repository for {@link ComplianceDiversityRequirement} — a real one-to-many collection
 * with its own payload ({@code minimumDistinctValues}), not a {@code DatabaseClient}-backed pure
 * association table (research.md §3).
 */
public interface ComplianceDiversityRequirementRepository
        extends ReactiveCrudRepository<ComplianceDiversityRequirement, UUID> {

    Mono<Boolean> existsByCustomFieldDefinitionId(UUID customFieldDefinitionId);
}

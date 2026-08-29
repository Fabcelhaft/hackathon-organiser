package net.fabcelhaft.hackathonorganiser.customfield;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

/**
 * Reactive repository for {@link CustomFieldDefinition} (T027).
 */
public interface CustomFieldDefinitionRepository extends ReactiveCrudRepository<CustomFieldDefinition, UUID> {
}

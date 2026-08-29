package net.fabcelhaft.hackathonorganiser.skill;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

/**
 * Reactive repository for {@link Skill} (T026).
 *
 * <p>The two {@code IgnoreCase} derived queries back {@link SkillService}'s FR-008a
 * case-insensitive duplicate-name check on create and rename — mirroring the database's own
 * {@code unique index on lower(name)} guard (data-model.md "Skill") at the service layer so a
 * duplicate produces a friendly domain error instead of a raw constraint-violation exception.
 */
public interface SkillRepository extends ReactiveCrudRepository<Skill, UUID> {

    Mono<Boolean> existsByNameIgnoreCase(String name);

    Mono<Boolean> existsByNameIgnoreCaseAndIdNot(String name, UUID id);
}

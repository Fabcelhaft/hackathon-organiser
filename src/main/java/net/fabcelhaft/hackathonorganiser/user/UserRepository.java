package net.fabcelhaft.hackathonorganiser.user;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive repository for {@link User} (T010).
 */
public interface UserRepository extends ReactiveCrudRepository<User, UUID> {

    /**
     * Looks up a User by the identity provider's stable subject identifier — the only field a
     * returning login is matched on (edge case in spec.md: never match on mutable profile fields).
     */
    Mono<User> findByOidcSubject(String oidcSubject);

    /**
     * Every User holding the Organiser role, by display name — the assignable people for a Task
     * (feature 011 FR-005, FR-021). Loaded once per Task-list render and shared across every row, so
     * the per-row assignee dropdown is not an N+1 query (feature 011 research.md §9).
     */
    Flux<User> findByOrganiserTrueOrderByDisplayNameAsc();
}

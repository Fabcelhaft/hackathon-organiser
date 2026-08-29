package net.fabcelhaft.hackathonorganiser.user;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
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
}

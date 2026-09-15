package net.fabcelhaft.hackathonorganiser.user;

import java.time.Instant;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.event.EventPayloadFactory;
import net.fabcelhaft.hackathonorganiser.event.EventPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Organiser-facing operations on {@link User} records (T015): list all, find by id, and toggle
 * the Organiser privilege (FR-004).
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final EventPublisher eventPublisher;
    private final EventPayloadFactory eventPayloadFactory;

    public UserService(UserRepository userRepository, EventPublisher eventPublisher, EventPayloadFactory eventPayloadFactory) {
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.eventPayloadFactory = eventPayloadFactory;
    }

    public Flux<User> findAll() {
        return userRepository.findAll();
    }

    public Mono<User> findById(UUID id) {
        return userRepository.findById(id);
    }

    /**
     * Grants or revokes the Organiser privilege for the User with the given id (FR-004, FR-005).
     * Completes empty if no such User exists. Publishes {@code ORGANISER_ROLE_ADDED}/{@code
     * ORGANISER_ROLE_REMOVED} (research.md §6) only when the privilege actually flips — calling
     * this with the User's current value is a no-op that must not re-fire the Event.
     */
    public Mono<User> setOrganiser(UUID id, boolean organiser) {
        return userRepository.findById(id).flatMap(user -> {
            boolean oldValue = user.isOrganiser();
            user.setOrganiser(organiser);
            user.setUpdatedAt(Instant.now());
            return userRepository.save(user).doOnNext(saved -> {
                if (oldValue != organiser) {
                    eventPublisher.publish(
                            organiser
                                    ? eventPayloadFactory.organiserRoleAdded(saved)
                                    : eventPayloadFactory.organiserRoleRemoved(saved));
                }
            });
        });
    }
}

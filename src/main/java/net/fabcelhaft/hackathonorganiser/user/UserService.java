package net.fabcelhaft.hackathonorganiser.user;

import java.time.Instant;
import java.util.UUID;
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

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Flux<User> findAll() {
        return userRepository.findAll();
    }

    public Mono<User> findById(UUID id) {
        return userRepository.findById(id);
    }

    /**
     * Grants or revokes the Organiser privilege for the User with the given id (FR-004, FR-005).
     * Completes empty if no such User exists.
     */
    public Mono<User> setOrganiser(UUID id, boolean organiser) {
        return userRepository.findById(id)
                .flatMap(user -> {
                    user.setOrganiser(organiser);
                    user.setUpdatedAt(Instant.now());
                    return userRepository.save(user);
                });
    }
}

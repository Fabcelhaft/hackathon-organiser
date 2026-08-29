package net.fabcelhaft.hackathonorganiser.content;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive repository for {@link ContentPage} (T039).
 */
public interface ContentPageRepository extends ReactiveCrudRepository<ContentPage, UUID> {

    /** The Info section listing order (FR-018, FR-020a): ascending by {@code sortIndex}, tie-broken by {@code createdAt}. */
    Flux<ContentPage> findAllByOrderBySortIndexAscCreatedAtAsc();

    /** The Content Page currently designated as the homepage's right-column content, if any (FR-019). */
    Mono<ContentPage> findByIsHomepageTrue();
}

package net.fabcelhaft.hackathonorganiser.content;

import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive repository for {@link ContentPage} (T039; Feature 008 T005).
 */
public interface ContentPageRepository extends ReactiveCrudRepository<ContentPage, UUID> {

    /**
     * The Info menu order (FR-002): ascending by {@code sortIndex}, tie-broken alphabetically by
     * {@code title} so the order is fully deterministic across loads (SC-008).
     */
    Flux<ContentPage> findAllByOrderBySortIndexAscTitleAsc();

    /**
     * The Content Page currently designated for {@code context}, if any (FR-013). Only meaningful
     * for a non-{@code NONE} context — {@code NONE} is held by any number of pages, so querying it
     * through a {@code Mono} would fail with an incorrect-result-size error.
     */
    Mono<ContentPage> findByContext(ContentPageContext context);

    /**
     * The highest {@code sort_index} in use, or {@code -1} when no Content Page exists — so
     * {@code ContentPageService#nextSortIndex} yields {@code 0} for the very first page (FR-019b).
     */
    @Query("SELECT COALESCE(MAX(sort_index), -1) FROM content_pages")
    Mono<Integer> findMaxSortIndex();
}

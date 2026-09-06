package net.fabcelhaft.hackathonorganiser.content;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read paths for {@link ContentPage} (T041; Feature 008 T007): the page designated for a given
 * {@link ContentPageContext} (homepage, topic creation, user registration), the Info wiki's menu
 * (every undesignated page, FR-014) with its default and per-id rendered detail, and the sort-index
 * pre-fill for the New page form — all markdown -> HTML conversion goes through
 * {@link MarkdownRenderer}, the single sanitization boundary (research.md §1).
 */
@Service
public class ContentPageService {

    private final ContentPageRepository contentPageRepository;
    private final MarkdownRenderer markdownRenderer;

    public ContentPageService(ContentPageRepository contentPageRepository, MarkdownRenderer markdownRenderer) {
        this.contentPageRepository = contentPageRepository;
        this.markdownRenderer = markdownRenderer;
    }

    /** The Content Page currently designated for a non-{@code NONE} {@code context}, if any (FR-013). */
    public Mono<ContentPage> findByContext(ContentPageContext context) {
        return contentPageRepository.findByContext(context);
    }

    /**
     * The Info menu (FR-001, FR-002, FR-014): every Content Page with {@code context == NONE},
     * ordered ascending by {@code sortIndex}, ties broken alphabetically by {@code title}.
     */
    public Flux<ContentPage> findInfoList() {
        return contentPageRepository
                .findAllByOrderBySortIndexAscTitleAsc()
                .filter(page -> page.getContext() == ContentPageContext.NONE);
    }

    /**
     * One undesignated Content Page rendered as sanitized HTML (FR-005, FR-006). Completes empty
     * if {@code id} is unknown, deleted, <em>or currently designated for a context</em> — a
     * designated page is never reachable at {@code /info/{id}} (FR-008, FR-014).
     */
    public Mono<RenderedContentPage> findRenderedDetail(UUID id) {
        return contentPageRepository
                .findById(id)
                .filter(page -> page.getContext() == ContentPageContext.NONE)
                .map(this::render);
    }

    /** The wiki's default page (FR-004): the first entry of {@link #findInfoList()}, rendered. Empty if none. */
    public Mono<RenderedContentPage> findRenderedDefault() {
        return findInfoList().next().map(this::render);
    }

    /**
     * The page designated for {@code context}, rendered as sanitized HTML. Completes empty if none
     * is currently designated — every caller treats that as the valid "render unchanged" state
     * (FR-017), never an error.
     */
    public Mono<RenderedContentPage> findRenderedByContext(ContentPageContext context) {
        return findByContext(context).map(this::render);
    }

    /**
     * The pre-filled {@code sort_index} for the New page form (FR-019a/FR-019b): one above the
     * highest index in use, or {@code 0} when no Content Page exists yet.
     */
    public Mono<Integer> nextSortIndex() {
        return contentPageRepository.findMaxSortIndex().map(max -> max + 1).defaultIfEmpty(0);
    }

    private RenderedContentPage render(ContentPage page) {
        return new RenderedContentPage(page, markdownRenderer.render(page.getBodyMarkdown()));
    }

    // --- Organiser-only management (T047; FR-019, FR-020a, FR-037) -------------------------------

    /** Every Content Page, designated or not, for management (contracts/wiki-info-and-content-pages.md). */
    public Flux<ContentPage> findAll() {
        return contentPageRepository.findAll();
    }

    /** A single Content Page by id, for the management edit form. */
    public Mono<ContentPage> findById(UUID id) {
        return contentPageRepository.findById(id);
    }

    /**
     * Creates a Content Page, rejecting a blank {@code title}/{@code body_markdown} with a
     * {@link ContentPageConflictException} (FR-037). If {@code context} is not {@code NONE}, first
     * clears that same context from whichever page currently holds it (FR-013) — the partial
     * unique index {@code content_pages_context_key} is the concurrency-safe backstop.
     */
    public Mono<ContentPage> create(String title, String bodyMarkdown, int sortIndex, ContentPageContext context) {
        if (isBlank(title) || isBlank(bodyMarkdown)) {
            return Mono.error(new ContentPageConflictException("title and body_markdown are required"));
        }
        return unsetPreviousContextIfNeeded(context, null).then(Mono.defer(() -> {
            ContentPage page = new ContentPage();
            page.setTitle(title);
            page.setBodyMarkdown(bodyMarkdown);
            page.setSortIndex(sortIndex);
            page.setContext(context);
            Instant now = Instant.now();
            page.setCreatedAt(now);
            page.setUpdatedAt(now);
            return contentPageRepository.save(page);
        }));
    }

    /**
     * Updates a Content Page's {@code title}/{@code bodyMarkdown}/{@code sortIndex} (FR-020a) and
     * context designation (FR-012, FR-013), with the same blank-field rejection and swap as
     * {@link #create}. Completes empty if no Content Page exists with the given id.
     */
    public Mono<ContentPage> update(
            UUID id, String title, String bodyMarkdown, int sortIndex, ContentPageContext context) {
        if (isBlank(title) || isBlank(bodyMarkdown)) {
            return Mono.error(new ContentPageConflictException("title and body_markdown are required"));
        }
        return contentPageRepository.findById(id).flatMap(page -> unsetPreviousContextIfNeeded(context, id)
                .then(Mono.defer(() -> {
                    page.setTitle(title);
                    page.setBodyMarkdown(bodyMarkdown);
                    page.setSortIndex(sortIndex);
                    page.setContext(context);
                    page.setUpdatedAt(Instant.now());
                    return contentPageRepository.save(page);
                })));
    }

    /** Deletes a Content Page. Completes empty if no Content Page exists with the given id. */
    public Mono<ContentPage> delete(UUID id) {
        return contentPageRepository.findById(id).flatMap(page -> contentPageRepository.deleteById(id).thenReturn(page));
    }

    /**
     * Clears {@code context} from whichever page (other than {@code excludeId}) currently holds it
     * — only that one context, never the other two (FR-013). No-op for {@code NONE}.
     */
    private Mono<Void> unsetPreviousContextIfNeeded(ContentPageContext context, UUID excludeId) {
        if (context == ContentPageContext.NONE) {
            return Mono.empty();
        }
        return contentPageRepository
                .findByContext(context)
                .filter(existing -> !Objects.equals(existing.getId(), excludeId))
                .flatMap(existing -> {
                    existing.setContext(ContentPageContext.NONE);
                    return contentPageRepository.save(existing);
                })
                .then();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** A Content Page plus its sanitized, rendered HTML body (the only form ever placed in a {@code th:utext}). */
    public record RenderedContentPage(ContentPage page, String bodyHtml) {}
}

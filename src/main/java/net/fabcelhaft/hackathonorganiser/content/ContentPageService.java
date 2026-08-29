package net.fabcelhaft.hackathonorganiser.content;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read paths for {@link ContentPage} (T041): the homepage's designated right-column page, the
 * Info section listing (every page except the homepage one, FR-018), and one page's rendered
 * detail view — all markdown -> HTML conversion goes through {@link MarkdownRenderer}, the single
 * sanitization boundary (research.md §1).
 */
@Service
public class ContentPageService {

    private final ContentPageRepository contentPageRepository;
    private final MarkdownRenderer markdownRenderer;

    public ContentPageService(ContentPageRepository contentPageRepository, MarkdownRenderer markdownRenderer) {
        this.contentPageRepository = contentPageRepository;
        this.markdownRenderer = markdownRenderer;
    }

    /** The Content Page currently designated as the homepage's right-column content, if any (FR-019). */
    public Mono<ContentPage> findHomepage() {
        return contentPageRepository.findByIsHomepageTrue();
    }

    /**
     * The Info section listing (FR-018): every Content Page except the one currently designated
     * as the homepage page, ordered ascending by {@code sortIndex} (tie-break {@code createdAt}).
     */
    public Flux<ContentPage> findInfoList() {
        return contentPageRepository.findAllByOrderBySortIndexAscCreatedAtAsc().filter(page -> !page.isHomepage());
    }

    /** One Content Page rendered as sanitized HTML (FR-036). Completes empty if {@code id} is unknown. */
    public Mono<RenderedContentPage> findRenderedDetail(UUID id) {
        return contentPageRepository.findById(id).map(this::render);
    }

    /**
     * The homepage's designated right-column page, rendered as sanitized HTML. Completes empty if
     * none is currently designated — {@code HomeController} treats that as a valid, renderable
     * empty/unset state, not an error (Edge Cases).
     */
    public Mono<RenderedContentPage> findRenderedHomepage() {
        return findHomepage().map(this::render);
    }

    private RenderedContentPage render(ContentPage page) {
        return new RenderedContentPage(page, markdownRenderer.render(page.getBodyMarkdown()));
    }

    // --- Organiser-only management (T047; FR-019, FR-020a, FR-037) -------------------------------

    /** Every Content Page, including the homepage one, for management (contracts/content-pages-and-info.md). */
    public Flux<ContentPage> findAll() {
        return contentPageRepository.findAll();
    }

    /** A single Content Page by id, for the management edit form. */
    public Mono<ContentPage> findById(UUID id) {
        return contentPageRepository.findById(id);
    }

    /**
     * Creates a Content Page, rejecting a blank {@code title}/{@code body_markdown} with a
     * {@link ContentPageConflictException} (FR-037). If {@code homepage} is {@code true}, first
     * un-designates whichever page currently holds that flag (FR-019) — the partial unique index
     * {@code content_pages_is_homepage_key} is the concurrency-safe backstop for this invariant.
     */
    public Mono<ContentPage> create(String title, String bodyMarkdown, int sortIndex, boolean homepage) {
        if (isBlank(title) || isBlank(bodyMarkdown)) {
            return Mono.error(new ContentPageConflictException("title and body_markdown are required"));
        }
        return unsetPreviousHomepageIfNeeded(homepage, null).then(Mono.defer(() -> {
            ContentPage page = new ContentPage();
            page.setTitle(title);
            page.setBodyMarkdown(bodyMarkdown);
            page.setSortIndex(sortIndex);
            page.setHomepage(homepage);
            Instant now = Instant.now();
            page.setCreatedAt(now);
            page.setUpdatedAt(now);
            return contentPageRepository.save(page);
        }));
    }

    /**
     * Updates a Content Page's {@code title}/{@code bodyMarkdown}/{@code sortIndex} (FR-020a) and
     * homepage designation (FR-019), with the same blank-field rejection and un-designation swap
     * as {@link #create}. Completes empty if no Content Page exists with the given id.
     */
    public Mono<ContentPage> update(UUID id, String title, String bodyMarkdown, int sortIndex, boolean homepage) {
        if (isBlank(title) || isBlank(bodyMarkdown)) {
            return Mono.error(new ContentPageConflictException("title and body_markdown are required"));
        }
        return contentPageRepository.findById(id).flatMap(page -> unsetPreviousHomepageIfNeeded(homepage, id)
                .then(Mono.defer(() -> {
                    page.setTitle(title);
                    page.setBodyMarkdown(bodyMarkdown);
                    page.setSortIndex(sortIndex);
                    page.setHomepage(homepage);
                    page.setUpdatedAt(Instant.now());
                    return contentPageRepository.save(page);
                })));
    }

    /** Deletes a Content Page. Completes empty if no Content Page exists with the given id. */
    public Mono<ContentPage> delete(UUID id) {
        return contentPageRepository.findById(id).flatMap(page -> contentPageRepository.deleteById(id).thenReturn(page));
    }

    private Mono<Void> unsetPreviousHomepageIfNeeded(boolean designatingHomepage, UUID excludeId) {
        if (!designatingHomepage) {
            return Mono.empty();
        }
        return contentPageRepository
                .findByIsHomepageTrue()
                .filter(existing -> !existing.getId().equals(excludeId))
                .flatMap(existing -> {
                    existing.setHomepage(false);
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

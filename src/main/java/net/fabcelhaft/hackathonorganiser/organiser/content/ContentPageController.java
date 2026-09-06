package net.fabcelhaft.hackathonorganiser.organiser.content;

import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.content.ContentPageConflictException;
import net.fabcelhaft.hackathonorganiser.content.ContentPageContext;
import net.fabcelhaft.hackathonorganiser.content.ContentPageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.reactive.result.view.Rendering;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Organiser-only Content Page management (T048; Feature 008 T019;
 * contracts/wiki-info-and-content-pages.md): create, edit, delete, reorder (FR-020a), and context
 * designation via a single-select (008 FR-012a). Access to every route here is restricted to
 * {@code ROLE_ORGANISER} by {@code SecurityConfig}'s {@code /organiser/**} path rule (FR-021).
 *
 * <p>{@code sort_index} is validated strictly at this controller boundary (research.md §7): a
 * blank or non-numeric value re-renders the form with an error instead of silently defaulting to
 * {@code 0} (008 FR-019), mirroring how {@code TopicSelfServiceController} validates required
 * fields before invoking its service.
 */
@Controller
@RequestMapping("/organiser/content-pages")
public class ContentPageController {

    static final String SORT_INDEX_REQUIRED_MESSAGE = "Sort index is required and must be a whole number";

    private final ContentPageService contentPageService;

    public ContentPageController(ContentPageService contentPageService) {
        this.contentPageService = contentPageService;
    }

    @GetMapping
    public Mono<Rendering> list() {
        return Mono.just(Rendering.view("organiser/content-pages/list")
                .modelAttribute("pages", contentPageService.findAll())
                .build());
    }

    /** The New page form, with {@code sort_index} pre-filled to the next free value (008 FR-019a/b). */
    @GetMapping("/new")
    public Mono<Rendering> newForm() {
        return contentPageService
                .nextSortIndex()
                .map(nextSortIndex -> Rendering.view("organiser/content-pages/form")
                        .modelAttribute("sortIndex", nextSortIndex)
                        .modelAttribute("context", ContentPageContext.NONE)
                        .build());
    }

    @PostMapping
    public Mono<Rendering> create(ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> {
            String title = form.getFirst("title");
            String bodyMarkdown = form.getFirst("body_markdown");
            String sortIndexRaw = form.getFirst("sort_index");
            ContentPageContext context = ContentPageContext.fromFormValue(form.getFirst("context"));
            return Mono.fromCallable(() -> parseSortIndex(sortIndexRaw))
                    .flatMap(sortIndex -> contentPageService.create(title, bodyMarkdown, sortIndex, context))
                    .<Rendering>map(page -> Rendering.redirectTo(
                                    "/organiser/content-pages/" + page.getId() + "/edit")
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(
                            ContentPageConflictException.class,
                            ex -> Mono.just(Rendering.view("organiser/content-pages/form")
                                    .modelAttribute("error", ex.getMessage())
                                    .modelAttribute("pageTitle", title)
                                    .modelAttribute("bodyMarkdown", bodyMarkdown)
                                    .modelAttribute("sortIndex", sortIndexRaw)
                                    .modelAttribute("context", context)
                                    .build()));
        });
    }

    @GetMapping("/{id}/edit")
    public Mono<Rendering> editForm(@PathVariable UUID id) {
        return contentPageService
                .findById(id)
                .map(page -> Rendering.view("organiser/content-pages/form")
                        .modelAttribute("pageId", id)
                        .modelAttribute("pageTitle", page.getTitle())
                        .modelAttribute("bodyMarkdown", page.getBodyMarkdown())
                        .modelAttribute("sortIndex", page.getSortIndex())
                        .modelAttribute("context", page.getContext())
                        .build())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping("/{id}")
    public Mono<Rendering> update(@PathVariable UUID id, ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> {
            String title = form.getFirst("title");
            String bodyMarkdown = form.getFirst("body_markdown");
            String sortIndexRaw = form.getFirst("sort_index");
            ContentPageContext context = ContentPageContext.fromFormValue(form.getFirst("context"));
            return Mono.fromCallable(() -> parseSortIndex(sortIndexRaw))
                    .flatMap(sortIndex -> contentPageService.update(id, title, bodyMarkdown, sortIndex, context))
                    .<Rendering>map(page -> Rendering.redirectTo("/organiser/content-pages/" + id + "/edit")
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(
                            ContentPageConflictException.class,
                            ex -> Mono.just(Rendering.view("organiser/content-pages/form")
                                    .modelAttribute("error", ex.getMessage())
                                    .modelAttribute("pageId", id)
                                    .modelAttribute("pageTitle", title)
                                    .modelAttribute("bodyMarkdown", bodyMarkdown)
                                    .modelAttribute("sortIndex", sortIndexRaw)
                                    .modelAttribute("context", context)
                                    .build()))
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
        });
    }

    @PostMapping("/{id}/delete")
    public Mono<Rendering> delete(@PathVariable UUID id) {
        return contentPageService
                .delete(id)
                .<Rendering>map(page -> Rendering.redirectTo("/organiser/content-pages")
                        .status(HttpStatus.SEE_OTHER)
                        .build())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    /**
     * Strict {@code sort_index} parsing (008 FR-019): blank or non-numeric is a validation error,
     * never a silent {@code 0}. Thrown inside {@code Mono.fromCallable}, so it surfaces as the
     * same {@link ContentPageConflictException} error signal the blank-title path already uses.
     */
    private static int parseSortIndex(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ContentPageConflictException(SORT_INDEX_REQUIRED_MESSAGE);
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new ContentPageConflictException(SORT_INDEX_REQUIRED_MESSAGE);
        }
    }
}

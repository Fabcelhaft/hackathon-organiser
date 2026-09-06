package net.fabcelhaft.hackathonorganiser.info;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.content.ContentPage;
import net.fabcelhaft.hackathonorganiser.content.ContentPageService;
import net.fabcelhaft.hackathonorganiser.content.ContentPageService.RenderedContentPage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.reactive.result.view.Rendering;
import reactor.core.publisher.Mono;

/**
 * Participant-facing Info section as a single wiki-style view (Feature 008, T012/T027;
 * contracts/wiki-info-and-content-pages.md): both routes render {@code info/index} with the menu of
 * every undesignated Content Page (FR-001, FR-014) plus one page's sanitized HTML on the right —
 * the first page by menu order at {@code /info} (FR-004), the requested page at {@code /info/{id}}
 * (FR-005, FR-006). Visible to any authenticated user, Organisers included — sits outside
 * {@code /organiser/**}.
 *
 * <p>Not-found is a {@link Rendering} with a 404 status rather than a thrown
 * {@code ResponseStatusException}: the exception path would bypass the view and drop the menu,
 * whereas FR-008 requires the wiki layout to stay usable (research.md §3). An empty menu routes to
 * the template's empty-state branch with a 200 at both routes (FR-020) — with no page to show,
 * "not found" would be the wrong message.
 */
@Controller
@RequestMapping("/info")
public class InfoController {

    private final ContentPageService contentPageService;

    public InfoController(ContentPageService contentPageService) {
        this.contentPageService = contentPageService;
    }

    @GetMapping
    public Mono<Rendering> index() {
        return contentPageService.findInfoList().collectList().flatMap(pages -> pages.isEmpty()
                ? Mono.just(emptyState(pages))
                : optional(contentPageService.findRenderedDefault()).map(rendered -> wiki(pages, rendered)));
    }

    @GetMapping("/{id}")
    public Mono<Rendering> detail(@PathVariable UUID id) {
        return contentPageService.findInfoList().collectList().flatMap(pages -> pages.isEmpty()
                ? Mono.just(emptyState(pages))
                : optional(contentPageService.findRenderedDetail(id)).map(rendered -> wiki(pages, rendered)));
    }

    private static Rendering emptyState(List<ContentPage> pages) {
        return Rendering.view("info/index")
                .modelAttribute("pages", pages)
                .modelAttribute("currentPage", null)
                .modelAttribute("currentPageId", null)
                .modelAttribute("bodyHtml", null)
                .modelAttribute("notFound", false)
                .build();
    }

    private static Rendering wiki(List<ContentPage> pages, Optional<RenderedContentPage> rendered) {
        Rendering.Builder<?> builder = Rendering.view("info/index").modelAttribute("pages", pages);
        if (rendered.isEmpty()) {
            return builder.modelAttribute("currentPage", null)
                    .modelAttribute("currentPageId", null)
                    .modelAttribute("bodyHtml", null)
                    .modelAttribute("notFound", true)
                    .status(HttpStatus.NOT_FOUND)
                    .build();
        }
        RenderedContentPage current = rendered.get();
        return builder.modelAttribute("currentPage", current.page())
                .modelAttribute("currentPageId", current.page().getId())
                .modelAttribute("bodyHtml", current.bodyHtml())
                .modelAttribute("notFound", false)
                .build();
    }

    /** Lifts an empty {@code Mono} into a present value, so the not-found branch is a plain mapping. */
    private static <T> Mono<Optional<T>> optional(Mono<T> mono) {
        return mono.map(Optional::of).defaultIfEmpty(Optional.empty());
    }
}

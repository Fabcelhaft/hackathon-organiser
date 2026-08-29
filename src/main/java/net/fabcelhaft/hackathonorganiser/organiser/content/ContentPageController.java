package net.fabcelhaft.hackathonorganiser.organiser.content;

import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.content.ContentPageConflictException;
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
 * Organiser-only Content Page management (T048; contracts/content-pages-and-info.md): create,
 * edit, delete, reorder (FR-020a), and homepage designation (FR-019). Access to every route here
 * is restricted to {@code ROLE_ORGANISER} by {@code SecurityConfig}'s {@code /organiser/**} path
 * rule (FR-021).
 */
@Controller
@RequestMapping("/organiser/content-pages")
public class ContentPageController {

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

    @GetMapping("/new")
    public Mono<Rendering> newForm() {
        return Mono.just(Rendering.view("organiser/content-pages/form").build());
    }

    @PostMapping
    public Mono<Rendering> create(ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> {
            String title = form.getFirst("title");
            String bodyMarkdown = form.getFirst("body_markdown");
            int sortIndex = parseIntOrZero(form.getFirst("sort_index"));
            boolean homepage = isChecked(form.get("is_homepage"));
            return contentPageService
                    .create(title, bodyMarkdown, sortIndex, homepage)
                    .<Rendering>map(page -> Rendering.redirectTo(
                                    "/organiser/content-pages/" + page.getId() + "/edit")
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(
                            ContentPageConflictException.class,
                            ex -> Mono.just(Rendering.view("organiser/content-pages/form")
                                    .modelAttribute("error", ex.getMessage())
                                    .modelAttribute("title", title)
                                    .modelAttribute("bodyMarkdown", bodyMarkdown)
                                    .modelAttribute("sortIndex", sortIndex)
                                    .modelAttribute("homepage", homepage)
                                    .build()));
        });
    }

    @GetMapping("/{id}/edit")
    public Mono<Rendering> editForm(@PathVariable UUID id) {
        return contentPageService
                .findById(id)
                .map(page -> Rendering.view("organiser/content-pages/form")
                        .modelAttribute("pageId", id)
                        .modelAttribute("title", page.getTitle())
                        .modelAttribute("bodyMarkdown", page.getBodyMarkdown())
                        .modelAttribute("sortIndex", page.getSortIndex())
                        .modelAttribute("homepage", page.isHomepage())
                        .build())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping("/{id}")
    public Mono<Rendering> update(@PathVariable UUID id, ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> {
            String title = form.getFirst("title");
            String bodyMarkdown = form.getFirst("body_markdown");
            int sortIndex = parseIntOrZero(form.getFirst("sort_index"));
            boolean homepage = isChecked(form.get("is_homepage"));
            return contentPageService
                    .update(id, title, bodyMarkdown, sortIndex, homepage)
                    .<Rendering>map(page -> Rendering.redirectTo("/organiser/content-pages/" + id + "/edit")
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(
                            ContentPageConflictException.class,
                            ex -> Mono.just(Rendering.view("organiser/content-pages/form")
                                    .modelAttribute("error", ex.getMessage())
                                    .modelAttribute("pageId", id)
                                    .modelAttribute("title", title)
                                    .modelAttribute("bodyMarkdown", bodyMarkdown)
                                    .modelAttribute("sortIndex", sortIndex)
                                    .modelAttribute("homepage", homepage)
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

    private static int parseIntOrZero(String raw) {
        try {
            return raw == null || raw.isBlank() ? 0 : Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static boolean isChecked(List<String> values) {
        return values != null && values.contains("true");
    }
}

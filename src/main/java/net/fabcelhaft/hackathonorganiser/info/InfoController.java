package net.fabcelhaft.hackathonorganiser.info;

import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.content.ContentPageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.reactive.result.view.Rendering;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Participant-facing Info section (T042; contracts/content-pages-and-info.md): every Content Page
 * except the one designated as the homepage page (FR-018), each individually viewable rendered as
 * sanitized HTML (FR-036). Visible to any authenticated user, Organisers included (spec
 * Assumptions) — sits outside {@code /organiser/**}.
 */
@Controller
@RequestMapping("/info")
public class InfoController {

    private final ContentPageService contentPageService;

    public InfoController(ContentPageService contentPageService) {
        this.contentPageService = contentPageService;
    }

    @GetMapping
    public Mono<Rendering> list() {
        return Mono.just(Rendering.view("info/list")
                .modelAttribute("pages", contentPageService.findInfoList())
                .build());
    }

    @GetMapping("/{id}")
    public Mono<Rendering> detail(@PathVariable UUID id) {
        return contentPageService
                .findRenderedDetail(id)
                .map(rendered -> Rendering.view("info/detail")
                        .modelAttribute("page", rendered.page())
                        .modelAttribute("bodyHtml", rendered.bodyHtml())
                        .build())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }
}

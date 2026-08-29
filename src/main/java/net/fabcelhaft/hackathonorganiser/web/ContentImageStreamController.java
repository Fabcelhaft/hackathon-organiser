package net.fabcelhaft.hackathonorganiser.web;

import java.time.Duration;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.content.ContentImageService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Serves a Content Image's raw bytes (T059; research.md §2). Deliberately outside
 * {@code /organiser/**} — any authenticated user (not just Organisers) must be able to load an
 * image embedded in a page they're allowed to view (Info pages and the homepage are visible to
 * every authenticated user, spec Assumptions); {@code SecurityConfig}'s existing
 * {@code .anyExchange().authenticated()} default already covers this route correctly, no new
 * security rule needed.
 *
 * <p>A long-lived {@code Cache-Control} header is safe here: the bytes/content-type of a given
 * {@code id} never change in place — only {@code alt_text} is ever editable (FR-025b), and that
 * isn't served from this route.
 */
@RestController
public class ContentImageStreamController {

    private final ContentImageService contentImageService;

    public ContentImageStreamController(ContentImageService contentImageService) {
        this.contentImageService = contentImageService;
    }

    @GetMapping("/content-images/{id}")
    public Mono<ResponseEntity<byte[]>> stream(@PathVariable UUID id) {
        return contentImageService
                .findById(id)
                .map(image -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(image.getContentType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic())
                        .body(image.getData()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }
}

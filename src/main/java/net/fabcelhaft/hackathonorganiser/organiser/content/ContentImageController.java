package net.fabcelhaft.hackathonorganiser.organiser.content;

import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.content.ContentImageConflictException;
import net.fabcelhaft.hackathonorganiser.content.ContentImageService;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
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
 * Organiser-only Content Image library management (T058; contracts/content-images.md): upload,
 * alt-text edit, delete. Access to every route here is restricted to {@code ROLE_ORGANISER} by
 * {@code SecurityConfig}'s {@code /organiser/**} path rule (FR-027) — the byte-serving route lives
 * separately, outside {@code /organiser/**}, in {@code web.ContentImageStreamController}
 * (research.md §2).
 */
@Controller
@RequestMapping("/organiser/content-images")
public class ContentImageController {

    private final ContentImageService contentImageService;

    public ContentImageController(ContentImageService contentImageService) {
        this.contentImageService = contentImageService;
    }

    @GetMapping
    public Mono<Rendering> list() {
        return Mono.just(Rendering.view("organiser/content-images/list")
                .modelAttribute("images", contentImageService.findAll())
                .build());
    }

    @PostMapping
    public Mono<Rendering> upload(ServerWebExchange exchange) {
        return exchange.getMultipartData().flatMap(parts -> {
            Part filePart = parts.getFirst("file");
            Part altTextPart = parts.getFirst("alt_text");
            String altText = altTextPart instanceof FormFieldPart formField ? formField.value() : null;
            if (!(filePart instanceof FilePart file)) {
                return errorList("Please choose a file to upload");
            }
            String contentType =
                    file.headers().getContentType() != null ? file.headers().getContentType().toString() : null;
            return DataBufferUtils.join(file.content())
                    .map(buffer -> {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        DataBufferUtils.release(buffer);
                        return bytes;
                    })
                    .flatMap(bytes -> contentImageService.upload(bytes, contentType, altText))
                    .<Rendering>map(image -> Rendering.redirectTo("/organiser/content-images")
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(ContentImageConflictException.class, ex -> errorList(ex.getMessage()));
        });
    }

    @PostMapping("/{id}/alt-text")
    public Mono<Rendering> updateAltText(@PathVariable UUID id, ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> {
            String altText = form.getFirst("alt_text");
            return contentImageService
                    .updateAltText(id, altText)
                    .<Rendering>map(image -> Rendering.redirectTo("/organiser/content-images")
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(ContentImageConflictException.class, ex -> errorList(ex.getMessage()))
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
        });
    }

    @PostMapping("/{id}/delete")
    public Mono<Rendering> delete(@PathVariable UUID id) {
        return contentImageService
                .delete(id)
                .<Rendering>map(image -> Rendering.redirectTo("/organiser/content-images")
                        .status(HttpStatus.SEE_OTHER)
                        .build())
                .onErrorResume(ContentImageConflictException.class, ex -> errorList(ex.getMessage()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    private Mono<Rendering> errorList(String error) {
        return Mono.just(Rendering.view("organiser/content-images/list")
                .modelAttribute("error", error)
                .modelAttribute("images", contentImageService.findAll())
                .build());
    }
}

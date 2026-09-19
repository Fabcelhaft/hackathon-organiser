package net.fabcelhaft.hackathonorganiser.topics;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.topic.TopicAttachmentService;
import net.fabcelhaft.hackathonorganiser.topic.TopicService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Serves a Topic Attachment's raw bytes (010 T032; FR-019, FR-020). Deliberately outside
 * {@code /organiser/**} — every user who can see a Topic must be able to download its attachments,
 * and {@code SecurityConfig}'s {@code .anyExchange().authenticated()} default already covers this
 * route correctly, exactly as it does for {@code web.ContentImageStreamController}.
 *
 * <p>Two guards, in order, and both answer 404 rather than 403 so that neither the existence of an
 * invisible Pending Topic nor of an attachment under another Topic is ever revealed:
 *
 * <ol>
 *   <li>{@link TopicService#findVisibleTo} — attachment visibility can therefore never exceed
 *       Topic visibility, by construction rather than by a rule repeated here (FR-020).
 *   <li>The attachment is looked up by the {@code (id, topicId)} <em>pair</em>, so a visible
 *       Topic's URL cannot be used to fetch some other Topic's attachment by guessing its id.
 * </ol>
 *
 * <p>Unlike Content Images, these bytes are visibility-gated, so the response is explicitly
 * {@code private, no-store} rather than long-lived and public. {@code Content-Disposition:
 * attachment} is what forces a download instead of inline rendering for every file type (FR-019);
 * Spring's {@link ContentDisposition} builder emits the RFC 5987 {@code filename*} form, which is
 * what keeps a non-ASCII file name intact in the header.
 */
@RestController
public class TopicAttachmentDownloadController {

    private final TopicService topicService;
    private final TopicAttachmentService topicAttachmentService;

    public TopicAttachmentDownloadController(
            TopicService topicService, TopicAttachmentService topicAttachmentService) {
        this.topicService = topicService;
        this.topicAttachmentService = topicAttachmentService;
    }

    @GetMapping("/topics/{topicId}/attachments/{attachmentId}")
    public Mono<ResponseEntity<byte[]>> download(
            @PathVariable UUID topicId,
            @PathVariable UUID attachmentId,
            @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        UUID viewerId = oidcUser.getUser().getId();
        boolean isOrganiser = oidcUser.getUser().isOrganiser();
        return topicService
                .findVisibleTo(topicId, viewerId, isOrganiser)
                .flatMap(topic -> topicAttachmentService.findForDownload(topicId, attachmentId))
                .map(attachment -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(attachment.getContentType()))
                        .header(
                                "Content-Disposition",
                                ContentDisposition.attachment()
                                        .filename(attachment.getFileName(), StandardCharsets.UTF_8)
                                        .build()
                                        .toString())
                        .cacheControl(CacheControl.noStore().cachePrivate())
                        .body(attachment.getData()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }
}

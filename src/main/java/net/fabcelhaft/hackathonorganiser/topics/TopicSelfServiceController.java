package net.fabcelhaft.hackathonorganiser.topics;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.audit.AuditActor;
import net.fabcelhaft.hackathonorganiser.content.ContentPageContext;
import net.fabcelhaft.hackathonorganiser.content.ContentPageService;
import net.fabcelhaft.hackathonorganiser.content.ContentPageService.RenderedContentPage;
import net.fabcelhaft.hackathonorganiser.content.MarkdownRenderer;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.topic.Topic;
import net.fabcelhaft.hackathonorganiser.topic.TopicAttachmentConflictException;
import net.fabcelhaft.hackathonorganiser.topic.TopicAttachmentService;
import net.fabcelhaft.hackathonorganiser.topic.TopicConflictException;
import net.fabcelhaft.hackathonorganiser.topic.TopicDiscoveryService;
import net.fabcelhaft.hackathonorganiser.topic.TopicService;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.reactive.result.view.Rendering;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Participant-facing propose/edit routes for Topics (T028; contracts/topics-self-service-and-
 * approval.md). Sits outside {@code /organiser/**} — only plain authentication is required to
 * propose (any authenticated user, regardless of Participant status) and authorship to edit
 * (FR-011).
 *
 * <p>{@link TopicService#findVisibleTo} enforces FR-012a's Pending-visibility rule before this
 * controller ever inspects authorship, giving exactly the 404-vs-403 split the contract requires:
 * unknown id or an invisible Pending Topic -> 404; visible but authored by someone else -> 403.
 *
 * <p>Feature 008 (FR-015, FR-017): the Content Page designated for {@code TOPIC_CREATION}, if any,
 * is rendered above the fields of the <em>propose</em> form only ({@link #newForm} and its
 * validation re-render) as {@code designatedContent}; {@link #editForm}/{@link #update} never
 * populate it, so the edit path is naturally unchanged.
 */
@Controller
@RequestMapping("/topics")
public class TopicSelfServiceController {

    private final TopicService topicService;
    private final TopicDiscoveryService topicDiscoveryService;
    private final OrganiserSettingsService organiserSettingsService;
    private final ContentPageService contentPageService;
    private final MarkdownRenderer markdownRenderer;
    private final TopicAttachmentService topicAttachmentService;

    public TopicSelfServiceController(
            TopicService topicService,
            TopicDiscoveryService topicDiscoveryService,
            OrganiserSettingsService organiserSettingsService,
            ContentPageService contentPageService,
            MarkdownRenderer markdownRenderer,
            TopicAttachmentService topicAttachmentService) {
        this.topicService = topicService;
        this.topicDiscoveryService = topicDiscoveryService;
        this.organiserSettingsService = organiserSettingsService;
        this.contentPageService = contentPageService;
        this.markdownRenderer = markdownRenderer;
        this.topicAttachmentService = topicAttachmentService;
    }

    /**
     * The Topic Details view (Story 9; contracts/topic-details.md; FR-030, FR-032): a sibling of
     * this controller's existing {@code /topics/{id}/edit} and {@code POST /topics/{id}} routes
     * (research.md §13), so it lives here rather than in a new controller. Visibility follows the
     * same {@link TopicService#findVisibleTo} rule those routes already apply — 404 for an unknown
     * or Pending-and-invisible Topic id, never a 403 that would leak its existence.
     */
    @GetMapping("/{id}")
    public Mono<Rendering> detail(@PathVariable UUID id, @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        UUID userId = oidcUser.getUser().getId();
        boolean isOrganiser = oidcUser.getUser().isOrganiser();
        return topicDiscoveryService
                .findTopicDetail(id, userId, isOrganiser)
                // Mono.zip would drop the whole view if findTopicDetail completed empty, which is
                // exactly the 404 case — so the visibility check stays the outer source and the
                // two display-only lookups are zipped underneath it.
                .flatMap(detail -> Mono.zip(
                        Mono.just(detail),
                        organiserSettingsService.current(),
                        topicAttachmentService.listFor(id).collectList()))
                .map(tuple -> Rendering.view("topics/detail")
                        .modelAttribute("detail", tuple.getT1())
                        // Feature 010 FR-018: metadata only — the bytes never reach this page.
                        .modelAttribute("attachments", tuple.getT3())
                        // Feature 010 FR-001: the description is markdown, rendered through the
                        // single sanitization boundary (MarkdownRenderer) at display time. Built
                        // here rather than inside TopicDetailView so the `topic` package keeps no
                        // dependency on `content` (research.md §4).
                        .modelAttribute(
                                "descriptionHtml",
                                markdownRenderer.render(tuple.getT1().topic().getDescription()))
                        .modelAttribute(
                                "complianceVisible",
                                isOrganiser || tuple.getT2().isComplianceVisibleToParticipants())
                        .modelAttribute("teamsLinksEnabled", tuple.getT2().isTeamsLinksEnabled())
                        .build())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @GetMapping("/new")
    public Mono<Rendering> newForm(@AuthenticationPrincipal HackathonOidcUser oidcUser) {
        return Mono.zip(topicService.allSkills().collectList(), designatedContent())
                .map(tuple -> Rendering.view("topics/form")
                        .modelAttribute("allSkills", tuple.getT1())
                        .modelAttribute("selectedSkillIds", List.<UUID>of())
                        .modelAttribute("designatedContent", tuple.getT2().orElse(null))
                        .build());
    }

    /** The TOPIC_CREATION-designated page, rendered — empty is a valid state (FR-017), so it is lifted into an Optional for zip. */
    private Mono<Optional<RenderedContentPage>> designatedContent() {
        return contentPageService
                .findRenderedByContext(ContentPageContext.TOPIC_CREATION)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    @PostMapping
    public Mono<Rendering> create(@AuthenticationPrincipal HackathonOidcUser oidcUser, ServerWebExchange exchange) {
        UUID userId = oidcUser.getUser().getId();
        return exchange.getFormData().flatMap(form -> {
            String name = form.getFirst("name");
            String description = form.getFirst("description");
            List<UUID> skillIds = toUuidList(form.get("skillIds"));
            return topicService
                    .propose(userId, name, description, skillIds, new AuditActor(userId, false))
                    .<Rendering>map(topic -> Rendering.redirectTo("/")
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(TopicConflictException.class, ex -> Mono.zip(
                                    topicService.allSkills().collectList(), designatedContent())
                            .map(tuple -> Rendering.view("topics/form")
                                    .modelAttribute("error", ex.getMessage())
                                    .modelAttribute("name", name)
                                    .modelAttribute("description", description)
                                    .modelAttribute("allSkills", tuple.getT1())
                                    .modelAttribute("selectedSkillIds", skillIds)
                                    .modelAttribute("designatedContent", tuple.getT2().orElse(null))
                                    .build()));
        });
    }

    @GetMapping("/{id}/edit")
    public Mono<Rendering> editForm(
            @PathVariable UUID id,
            @AuthenticationPrincipal HackathonOidcUser oidcUser,
            @RequestParam(name = "attachment", required = false) String attachment) {
        UUID userId = oidcUser.getUser().getId();
        return topicService
                .findVisibleTo(id, userId, oidcUser.getUser().isOrganiser())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(topic -> {
                    failIfNotAuthor(topic, userId);
                    return editFormModel(id, topic, null)
                            .map(rendering -> rendering.modelAttribute("notice", attachmentNotice(attachment)))
                            .map(Rendering.Builder::build);
                });
    }

    /**
     * Feature 010 (research.md §10): WebFlux has no flash attributes and this codebase has no
     * post-redirect message mechanism, so the upload/remove routes redirect back here with
     * {@code ?attachment=added|removed} and this turns it into a {@code role="status"} notice.
     * Any other value yields no notice, so a stale bookmark is harmless.
     */
    private static String attachmentNotice(String attachment) {
        if ("added".equals(attachment)) {
            return "Attachment uploaded.";
        }
        if ("removed".equals(attachment)) {
            return "Attachment removed.";
        }
        return null;
    }

    /**
     * The complete edit-form model, shared by {@link #editForm} and by every attachment-route
     * failure path (010 T031). Building it in one place is what stops a rejected upload
     * re-rendering a blank form: {@code name}/{@code description} come from the stored Topic,
     * since the attachment forms submit no text fields at all.
     */
    private Mono<Rendering.Builder> editFormModel(UUID id, Topic topic, String error) {
        return Mono.zip(
                        topicService.allSkills().collectList(),
                        topicService.findDetail(id),
                        topicAttachmentService.listFor(id).collectList())
                .map(tuple -> Rendering.view("topics/form")
                        .modelAttribute("topicId", id)
                        .modelAttribute("name", topic.getName())
                        .modelAttribute("description", topic.getDescription())
                        .modelAttribute("allSkills", tuple.getT1())
                        .modelAttribute("selectedSkillIds", tuple.getT2().skillIds())
                        .modelAttribute("attachments", tuple.getT3())
                        .modelAttribute("error", error));
    }

    /** Uploads one attachment to a Topic the caller authored (010 FR-012, FR-012a). */
    @PostMapping("/{id}/attachments")
    public Mono<Rendering> uploadAttachment(
            @PathVariable UUID id,
            @AuthenticationPrincipal HackathonOidcUser oidcUser,
            ServerWebExchange exchange) {
        UUID userId = oidcUser.getUser().getId();
        return topicService
                .findVisibleTo(id, userId, oidcUser.getUser().isOrganiser())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(topic -> {
                    failIfNotAuthor(topic, userId);
                    return storeUploadedPart(
                                    exchange, id, userId, true, new AuditActor(userId, false))
                            .<Rendering>map(saved -> Rendering.redirectTo(
                                            "/topics/" + id + "/edit?attachment=added")
                                    .status(HttpStatus.SEE_OTHER)
                                    .build())
                            .onErrorResume(TopicAttachmentConflictException.class, ex -> editFormModel(
                                            id, topic, ex.getMessage())
                                    .map(Rendering.Builder::build));
                });
    }

    /** Removes one attachment from a Topic the caller authored (010 FR-012, FR-012b). */
    @PostMapping("/{id}/attachments/{attachmentId}/delete")
    public Mono<Rendering> removeAttachment(
            @PathVariable UUID id,
            @PathVariable UUID attachmentId,
            @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        UUID userId = oidcUser.getUser().getId();
        return topicService
                .findVisibleTo(id, userId, oidcUser.getUser().isOrganiser())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(topic -> {
                    failIfNotAuthor(topic, userId);
                    return topicAttachmentService
                            .remove(id, attachmentId, userId, true, new AuditActor(userId, false))
                            .<Rendering>map(removed -> Rendering.redirectTo(
                                            "/topics/" + id + "/edit?attachment=removed")
                                    .status(HttpStatus.SEE_OTHER)
                                    .build())
                            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
                });
    }

    /**
     * Reads the {@code file} part of a multipart request into memory and hands it to the service —
     * the same shape {@code ContentImageController.upload} already uses. A request with no file
     * part at all is rejected here, before the service is involved, since there is nothing to pass.
     */
    private Mono<?> storeUploadedPart(
            ServerWebExchange exchange, UUID topicId, UUID userId, boolean requireAuthor, AuditActor actor) {
        return exchange.getMultipartData().flatMap(parts -> {
            Part filePart = parts.getFirst("file");
            if (!(filePart instanceof FilePart file)) {
                return Mono.error(new TopicAttachmentConflictException("Please choose a file to upload"));
            }
            String contentType = file.headers().getContentType() != null
                    ? file.headers().getContentType().toString()
                    : null;
            return DataBufferUtils.join(file.content())
                    .map(buffer -> {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        DataBufferUtils.release(buffer);
                        return bytes;
                    })
                    // An empty file yields no buffer at all, so the service's empty-file rule is
                    // only reachable when this default supplies the zero-length array.
                    .defaultIfEmpty(new byte[0])
                    .flatMap(bytes -> topicAttachmentService.upload(
                            topicId, userId, requireAuthor, file.filename(), contentType, bytes, actor));
        });
    }

    @PostMapping("/{id}")
    public Mono<Rendering> update(
            @PathVariable UUID id, @AuthenticationPrincipal HackathonOidcUser oidcUser, ServerWebExchange exchange) {
        UUID userId = oidcUser.getUser().getId();
        return topicService
                .findVisibleTo(id, userId, oidcUser.getUser().isOrganiser())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(topic -> {
                    failIfNotAuthor(topic, userId);
                    return exchange.getFormData().flatMap(form -> {
                        String name = form.getFirst("name");
                        String description = form.getFirst("description");
                        List<UUID> skillIds = toUuidList(form.get("skillIds"));
                        return topicService
                                .updateAsAuthor(
                                        id, userId, name, description, skillIds, new AuditActor(userId, false))
                                .<Rendering>map(saved -> Rendering.redirectTo("/")
                                        .status(HttpStatus.SEE_OTHER)
                                        .build())
                                .onErrorResume(TopicConflictException.class, ex -> topicService
                                        .allSkills()
                                        .collectList()
                                        .map(allSkills -> Rendering.view("topics/form")
                                                .modelAttribute("error", ex.getMessage())
                                                .modelAttribute("topicId", id)
                                                .modelAttribute("name", name)
                                                .modelAttribute("description", description)
                                                .modelAttribute("allSkills", allSkills)
                                                .modelAttribute("selectedSkillIds", skillIds)
                                                .build()));
                    });
                });
    }

    private static List<UUID> toUuidList(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().filter(s -> s != null && !s.isBlank()).map(UUID::fromString).toList();
    }

    /** Throws (synchronously, inside a flatMap) rather than returning an error Mono, since the
     * caller is already inside a flatMap whose lambda body continues past this call only for the
     * author — a thrown exception here is caught by Reactor and turned into an error signal on the
     * resulting Mono, same net effect as {@code Mono.error(...)} without an empty-Mono short-circuit
     * (an intermediate {@code Mono.empty()} step here would prevent the following flatMap/then stage
     * from ever running, even for a legitimate author). */
    private static void failIfNotAuthor(Topic topic, UUID userId) {
        if (!topic.getCreatedByUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}

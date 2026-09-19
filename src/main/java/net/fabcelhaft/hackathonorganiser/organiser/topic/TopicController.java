package net.fabcelhaft.hackathonorganiser.organiser.topic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.audit.AuditActor;
import net.fabcelhaft.hackathonorganiser.audit.AuditService;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceService;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceStatus;
import net.fabcelhaft.hackathonorganiser.content.MarkdownRenderer;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.topic.Topic;
import net.fabcelhaft.hackathonorganiser.topic.TopicAttachmentConflictException;
import net.fabcelhaft.hackathonorganiser.topic.TopicAttachmentService;
import net.fabcelhaft.hackathonorganiser.topic.TopicConflictException;
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
 * Organiser-only views for Topic create/view/edit and Skill associations (T049;
 * contracts/topic-management.md). Access to every route here is restricted to
 * {@code ROLE_ORGANISER} by {@code SecurityConfig}'s {@code /organiser/**} path rule (FR-022).
 *
 * <p>Per the contract's explicit note, the update route ({@code POST /organiser/topics/{id}})
 * takes only {@code name}/{@code description}/{@code skill_ids[]} — it never reads a
 * {@code created_by_user_id} field from the request at all, so the creator recorded at creation
 * (FR-015) cannot be reassigned through this route.
 *
 * <p>The list/detail views additionally surface each Topic's current active-Group status
 * (contracts/topic-management.md: "whether an active Group currently exists for it") by consulting
 * {@link GroupService} directly from this controller — the {@code group} domain depends on {@code
 * topic} (a Group always references exactly one Topic), not the reverse, so this lookup happens
 * here in the organiser web layer rather than inside {@link TopicService} itself.
 *
 * <p>The list view also surfaces each row's Compliance status, computed via {@link
 * ComplianceService#evaluate} the same way {@code GroupController}'s detail view already does —
 * this Organiser-only list is otherwise the one place an Organiser can't see it without opening
 * each Topic's active Group individually.
 */
@Controller
@RequestMapping("/organiser/topics")
public class TopicController {

    private final TopicService topicService;
    private final GroupService groupService;
    private final ComplianceService complianceService;
    private final OrganiserSettingsService organiserSettingsService;
    private final AuditService auditService;
    private final MarkdownRenderer markdownRenderer;
    private final TopicAttachmentService topicAttachmentService;

    public TopicController(
            TopicService topicService,
            GroupService groupService,
            ComplianceService complianceService,
            OrganiserSettingsService organiserSettingsService,
            AuditService auditService,
            MarkdownRenderer markdownRenderer,
            TopicAttachmentService topicAttachmentService) {
        this.topicService = topicService;
        this.groupService = groupService;
        this.complianceService = complianceService;
        this.organiserSettingsService = organiserSettingsService;
        this.auditService = auditService;
        this.markdownRenderer = markdownRenderer;
        this.topicAttachmentService = topicAttachmentService;
    }

    @GetMapping
    public Mono<Rendering> list() {
        return Mono.just(Rendering.view("organiser/topics/list")
                .modelAttribute("topics", topicService.findAll().concatMap(this::toRow))
                .build());
    }

    private Mono<TopicRow> toRow(Topic topic) {
        return activeGroupFor(topic.getId())
                .flatMap(opt -> opt.map(group -> complianceStatusFor(group)
                                .map(status -> new TopicRow(topic, group.getId(), status)))
                        .orElseGet(() -> Mono.just(new TopicRow(topic, null, Optional.empty()))));
    }

    // Reactor's Mono/Flux forbid a null onNext value, so the "no active Group" case is carried as
    // an empty Optional through the reactive chain and only unwrapped to a nullable UUID at the
    // point of building the final (non-null) POJO/Rendering — never as the Mono's own emitted item.
    private Mono<Optional<UUID>> activeGroupIdFor(UUID topicId) {
        return activeGroupFor(topicId).map(opt -> opt.map(Group::getId));
    }

    private Mono<Optional<Group>> activeGroupFor(UUID topicId) {
        return groupService.findActiveGroupForTopic(topicId).map(Optional::of).defaultIfEmpty(Optional.empty());
    }

    /**
     * The same {@link ComplianceService#evaluate} contract {@code GroupController} already uses for
     * its own detail view (research.md §5) — reused verbatim here so the two views can never
     * disagree.
     */
    private Mono<Optional<ComplianceStatus>> complianceStatusFor(Group group) {
        return groupService
                .activeMemberParticipantIds(group.getId())
                .flatMap(memberIds -> complianceService.evaluate(group, memberIds))
                .map(Optional::of);
    }

    /** The list view's per-row read model: a Topic, its active Group's id, and its Compliance status. */
    public record TopicRow(Topic topic, UUID activeGroupId, Optional<ComplianceStatus> complianceStatus) {}

    @GetMapping("/new")
    public Mono<Rendering> newForm() {
        return Mono.just(Rendering.view("organiser/topics/form")
                .modelAttribute("availableUsers", topicService.allUsers())
                .modelAttribute("allSkills", topicService.allSkills())
                .modelAttribute("selectedSkillIds", List.of())
                .build());
    }

    @PostMapping
    public Mono<Rendering> create(ServerWebExchange exchange, @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        // WebFlux's @RequestParam only ever reads URL query parameters, never a form-urlencoded
        // request body (unlike Spring MVC) — so form fields are read via ServerWebExchange.getFormData().
        return exchange.getFormData().flatMap(form -> {
            String name = form.getFirst("name");
            String description = form.getFirst("description");
            UUID createdByUserId = parseUuidOrNull(form.getFirst("created_by_user_id"));
            List<UUID> skillIds = toUuidList(form.get("skill_ids"));
            AuditActor actor = new AuditActor(oidcUser.getUser().getId(), true);
            return topicService
                    .create(name, description, createdByUserId, skillIds, actor)
                    .<Rendering>map(topic -> Rendering.redirectTo("/organiser/topics/" + topic.getId())
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(
                            TopicConflictException.class,
                            ex -> Mono.just(Rendering.view("organiser/topics/form")
                                    .modelAttribute("error", ex.getMessage())
                                    .modelAttribute("name", name)
                                    .modelAttribute("description", description)
                                    .modelAttribute("availableUsers", topicService.allUsers())
                                    .modelAttribute("allSkills", topicService.allSkills())
                                    .modelAttribute("selectedSkillIds", skillIds)
                                    .build()));
        });
    }

    @GetMapping("/{id}")
    public Mono<Rendering> detail(@PathVariable UUID id) {
        return topicService
                .findDetail(id)
                .flatMap(detail -> Mono.zip(
                                activeGroupIdFor(id),
                                organiserSettingsService.current(),
                                topicAttachmentService.listFor(id).collectList())
                        .map(tuple -> Rendering.view("organiser/topics/detail")
                                .modelAttribute("detail", detail)
                                // Feature 010 FR-018: metadata only, never the bytes.
                                .modelAttribute("attachments", tuple.getT3())
                                // Feature 010 FR-001: same rendering as the participant-facing
                                // detail view, so the two can never disagree.
                                .modelAttribute(
                                        "descriptionHtml",
                                        markdownRenderer.render(
                                                detail.topic().getDescription()))
                                .modelAttribute("activeGroupId", tuple.getT1().orElse(null))
                                .modelAttribute("teamsLinksEnabled", tuple.getT2().isTeamsLinksEnabled())
                                .build()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @GetMapping("/{id}/edit")
    public Mono<Rendering> editForm(
            @PathVariable UUID id, @RequestParam(name = "attachment", required = false) String attachment) {
        return editFormModel(id, null)
                .map(rendering -> rendering
                        .modelAttribute("notice", attachmentNotice(attachment))
                        .build())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    /**
     * The complete organiser edit-form model, shared by {@link #editForm} and by the attachment
     * routes' failure paths (010 T033). Built in one place so a rejected upload re-renders a fully
     * populated form — including {@code availableUsers} and {@code currentAuthorUserId}, without
     * which this form's Creator select would render empty. Completes empty for an unknown id.
     */
    private Mono<Rendering.Builder> editFormModel(UUID id, String error) {
        return topicService
                .findDetail(id)
                .flatMap(detail -> topicAttachmentService
                        .listFor(id)
                        .collectList()
                        .map(attachments -> Rendering.view("organiser/topics/form")
                                .modelAttribute("topicId", id)
                                .modelAttribute("name", detail.topic().getName())
                                .modelAttribute("description", detail.topic().getDescription())
                                .modelAttribute("allSkills", topicService.allSkills())
                                .modelAttribute("selectedSkillIds", detail.skillIds())
                                .modelAttribute("availableUsers", topicService.allUsers())
                                .modelAttribute(
                                        "currentAuthorUserId",
                                        detail.topic().getCreatedByUserId())
                                .modelAttribute("attachments", attachments)
                                .modelAttribute("error", error)));
    }

    /** See {@code TopicSelfServiceController.attachmentNotice} — same query-parameter mechanism. */
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
     * Uploads an attachment to any Topic (010 FR-013). {@code requireAuthor} is {@code false}: the
     * {@code /organiser/**} role rule in {@code SecurityConfig} is the authorization gate here, and
     * an Organiser may manage attachments on a Topic they did not author.
     */
    @PostMapping("/{id}/attachments")
    public Mono<Rendering> uploadAttachment(
            @PathVariable UUID id,
            ServerWebExchange exchange,
            @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        UUID userId = oidcUser.getUser().getId();
        AuditActor actor = new AuditActor(userId, true);
        return exchange.getMultipartData()
                .flatMap(parts -> {
                    Part filePart = parts.getFirst("file");
                    if (!(filePart instanceof FilePart file)) {
                        return Mono.error(
                                new TopicAttachmentConflictException("Please choose a file to upload"));
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
                            .defaultIfEmpty(new byte[0])
                            .flatMap(bytes -> topicAttachmentService.upload(
                                    id, userId, false, file.filename(), contentType, bytes, actor));
                })
                .<Rendering>map(saved -> Rendering.redirectTo("/organiser/topics/" + id + "/edit?attachment=added")
                        .status(HttpStatus.SEE_OTHER)
                        .build())
                .onErrorResume(TopicAttachmentConflictException.class, ex -> editFormModel(id, ex.getMessage())
                        .map(Rendering.Builder::build))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    /** Removes an attachment from any Topic (010 FR-013). */
    @PostMapping("/{id}/attachments/{attachmentId}/delete")
    public Mono<Rendering> removeAttachment(
            @PathVariable UUID id,
            @PathVariable UUID attachmentId,
            @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        UUID userId = oidcUser.getUser().getId();
        return topicAttachmentService
                .remove(id, attachmentId, userId, false, new AuditActor(userId, true))
                .<Rendering>map(removed -> Rendering.redirectTo(
                                "/organiser/topics/" + id + "/edit?attachment=removed")
                        .status(HttpStatus.SEE_OTHER)
                        .build())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping("/{id}")
    public Mono<Rendering> update(
            @PathVariable UUID id, ServerWebExchange exchange, @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        return exchange.getFormData().flatMap(form -> {
            String name = form.getFirst("name");
            String description = form.getFirst("description");
            List<UUID> skillIds = toUuidList(form.get("skill_ids"));
            UUID newAuthorUserId = parseUuidOrNull(form.getFirst("created_by_user_id"));
            AuditActor actor = new AuditActor(oidcUser.getUser().getId(), true);
            return topicService
                    .update(id, name, description, skillIds, actor)
                    .flatMap(topic -> newAuthorUserId == null
                            ? Mono.just(topic)
                            // FR-015 supersedes 002's immutability for this one Organiser-only
                            // route: a separate call to reassignAuthor, never a parameter on
                            // update() itself (data-model.md "Topic").
                            : topicService.reassignAuthor(id, newAuthorUserId, actor))
                    .<Rendering>map(topic -> Rendering.redirectTo("/organiser/topics/" + id)
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(
                            TopicConflictException.class,
                            ex -> Mono.just(Rendering.view("organiser/topics/form")
                                    .modelAttribute("error", ex.getMessage())
                                    .modelAttribute("topicId", id)
                                    .modelAttribute("name", name)
                                    .modelAttribute("description", description)
                                    .modelAttribute("allSkills", topicService.allSkills())
                                    .modelAttribute("selectedSkillIds", skillIds)
                                    .modelAttribute("availableUsers", topicService.allUsers())
                                    .build()))
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
        });
    }

    /**
     * A Topic's full audit history, most-recent-first (Story 2; contracts/audit-retrieval.md;
     * FR-007, FR-008, FR-011) — also what a Group's detail page's "Audit" link resolves to, for
     * that Group's own Topic (research.md §9). Unknown {@code id} -> 404.
     */
    @GetMapping("/{id}/audit")
    public Mono<Rendering> audit(@PathVariable UUID id) {
        return topicService
                .findById(id)
                .map(topic -> Rendering.view("organiser/topics/audit")
                        .modelAttribute("entries", auditService.findForTopic(id))
                        .build())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    /** Approves a Pending Topic (FR-014); a no-op if already Approved. */
    @PostMapping("/{id}/approve")
    public Mono<Rendering> approve(@PathVariable UUID id, @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        return topicService
                .approve(id, new AuditActor(oidcUser.getUser().getId(), true))
                .<Rendering>map(topic -> Rendering.redirectTo("/organiser/topics/" + id)
                        .status(HttpStatus.SEE_OTHER)
                        .build())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    private static UUID parseUuidOrNull(String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static List<UUID> toUuidList(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().filter(s -> s != null && !s.isBlank()).map(UUID::fromString).toList();
    }
}

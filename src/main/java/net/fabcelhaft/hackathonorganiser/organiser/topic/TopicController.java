package net.fabcelhaft.hackathonorganiser.organiser.topic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceService;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceStatus;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.topic.Topic;
import net.fabcelhaft.hackathonorganiser.topic.TopicConflictException;
import net.fabcelhaft.hackathonorganiser.topic.TopicService;
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

    public TopicController(
            TopicService topicService,
            GroupService groupService,
            ComplianceService complianceService,
            OrganiserSettingsService organiserSettingsService) {
        this.topicService = topicService;
        this.groupService = groupService;
        this.complianceService = complianceService;
        this.organiserSettingsService = organiserSettingsService;
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
    public Mono<Rendering> create(ServerWebExchange exchange) {
        // WebFlux's @RequestParam only ever reads URL query parameters, never a form-urlencoded
        // request body (unlike Spring MVC) — so form fields are read via ServerWebExchange.getFormData().
        return exchange.getFormData().flatMap(form -> {
            String name = form.getFirst("name");
            String description = form.getFirst("description");
            UUID createdByUserId = parseUuidOrNull(form.getFirst("created_by_user_id"));
            List<UUID> skillIds = toUuidList(form.get("skill_ids"));
            return topicService
                    .create(name, description, createdByUserId, skillIds)
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
                .flatMap(detail -> Mono.zip(activeGroupIdFor(id), organiserSettingsService.current())
                        .map(tuple -> Rendering.view("organiser/topics/detail")
                                .modelAttribute("detail", detail)
                                .modelAttribute("activeGroupId", tuple.getT1().orElse(null))
                                .modelAttribute("teamsLinksEnabled", tuple.getT2().isTeamsLinksEnabled())
                                .build()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @GetMapping("/{id}/edit")
    public Mono<Rendering> editForm(@PathVariable UUID id) {
        return topicService
                .findDetail(id)
                .map(detail -> Rendering.view("organiser/topics/form")
                        .modelAttribute("topicId", id)
                        .modelAttribute("name", detail.topic().getName())
                        .modelAttribute("description", detail.topic().getDescription())
                        .modelAttribute("allSkills", topicService.allSkills())
                        .modelAttribute("selectedSkillIds", detail.skillIds())
                        .modelAttribute("availableUsers", topicService.allUsers())
                        .modelAttribute("currentAuthorUserId", detail.topic().getCreatedByUserId())
                        .build())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping("/{id}")
    public Mono<Rendering> update(@PathVariable UUID id, ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> {
            String name = form.getFirst("name");
            String description = form.getFirst("description");
            List<UUID> skillIds = toUuidList(form.get("skill_ids"));
            UUID newAuthorUserId = parseUuidOrNull(form.getFirst("created_by_user_id"));
            return topicService
                    .update(id, name, description, skillIds)
                    .flatMap(topic -> newAuthorUserId == null
                            ? Mono.just(topic)
                            // FR-015 supersedes 002's immutability for this one Organiser-only
                            // route: a separate call to reassignAuthor, never a parameter on
                            // update() itself (data-model.md "Topic").
                            : topicService.reassignAuthor(id, newAuthorUserId))
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

    /** Approves a Pending Topic (FR-014); a no-op if already Approved. */
    @PostMapping("/{id}/approve")
    public Mono<Rendering> approve(@PathVariable UUID id) {
        return topicService
                .approve(id)
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

package net.fabcelhaft.hackathonorganiser.organiser.topic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
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
 */
@Controller
@RequestMapping("/organiser/topics")
public class TopicController {

    private final TopicService topicService;
    private final GroupService groupService;

    public TopicController(TopicService topicService, GroupService groupService) {
        this.topicService = topicService;
        this.groupService = groupService;
    }

    @GetMapping
    public Mono<Rendering> list() {
        return Mono.just(Rendering.view("organiser/topics/list")
                .modelAttribute("topics", topicService.findAll().concatMap(this::toRow))
                .build());
    }

    private Mono<TopicRow> toRow(Topic topic) {
        return activeGroupIdFor(topic.getId()).map(opt -> new TopicRow(topic, opt.orElse(null)));
    }

    // Reactor's Mono/Flux forbid a null onNext value, so the "no active Group" case is carried as
    // an empty Optional through the reactive chain and only unwrapped to a nullable UUID at the
    // point of building the final (non-null) POJO/Rendering — never as the Mono's own emitted item.
    private Mono<Optional<UUID>> activeGroupIdFor(UUID topicId) {
        return groupService
                .findActiveGroupForTopic(topicId)
                .map(Group::getId)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    /** The list view's per-row read model: a Topic plus its active Group's id, if any. */
    public record TopicRow(Topic topic, UUID activeGroupId) {}

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
                .flatMap(detail -> activeGroupIdFor(id).map(opt -> Rendering.view("organiser/topics/detail")
                        .modelAttribute("detail", detail)
                        .modelAttribute("activeGroupId", opt.orElse(null))
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
                        .build())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping("/{id}")
    public Mono<Rendering> update(@PathVariable UUID id, ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> {
            String name = form.getFirst("name");
            String description = form.getFirst("description");
            List<UUID> skillIds = toUuidList(form.get("skill_ids"));
            return topicService
                    .update(id, name, description, skillIds)
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
                                    .build()))
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
        });
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

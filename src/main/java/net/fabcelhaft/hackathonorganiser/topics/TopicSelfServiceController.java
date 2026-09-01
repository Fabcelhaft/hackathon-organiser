package net.fabcelhaft.hackathonorganiser.topics;

import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantService;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.topic.Topic;
import net.fabcelhaft.hackathonorganiser.topic.TopicConflictException;
import net.fabcelhaft.hackathonorganiser.topic.TopicDiscoveryService;
import net.fabcelhaft.hackathonorganiser.topic.TopicService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * Participant-facing propose/edit routes for Topics (T028; contracts/topics-self-service-and-
 * approval.md). Sits outside {@code /organiser/**} — only plain authentication is required, plus
 * an Active Participant record to propose (Edge Cases: Standard users cannot propose) and
 * authorship to edit (FR-011).
 *
 * <p>{@link TopicService#findVisibleTo} enforces FR-012a's Pending-visibility rule before this
 * controller ever inspects authorship, giving exactly the 404-vs-403 split the contract requires:
 * unknown id or an invisible Pending Topic -> 404; visible but authored by someone else -> 403.
 */
@Controller
@RequestMapping("/topics")
public class TopicSelfServiceController {

    private final TopicService topicService;
    private final ParticipantService participantService;
    private final TopicDiscoveryService topicDiscoveryService;
    private final OrganiserSettingsService organiserSettingsService;

    public TopicSelfServiceController(
            TopicService topicService,
            ParticipantService participantService,
            TopicDiscoveryService topicDiscoveryService,
            OrganiserSettingsService organiserSettingsService) {
        this.topicService = topicService;
        this.participantService = participantService;
        this.topicDiscoveryService = topicDiscoveryService;
        this.organiserSettingsService = organiserSettingsService;
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
                .zipWith(organiserSettingsService.current())
                .map(tuple -> Rendering.view("topics/detail")
                        .modelAttribute("detail", tuple.getT1())
                        .modelAttribute(
                                "complianceVisible",
                                isOrganiser || tuple.getT2().isComplianceVisibleToParticipants())
                        .build())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @GetMapping("/new")
    public Mono<Rendering> newForm(@AuthenticationPrincipal HackathonOidcUser oidcUser) {
        return participantService
                .findByUserId(oidcUser.getUser().getId())
                .flatMap(participant -> topicService
                        .allSkills()
                        .collectList()
                        .map(allSkills -> Rendering.view("topics/form")
                                .modelAttribute("allSkills", allSkills)
                                .modelAttribute("selectedSkillIds", List.<UUID>of())
                                .build()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)));
    }

    @PostMapping
    public Mono<Rendering> create(@AuthenticationPrincipal HackathonOidcUser oidcUser, ServerWebExchange exchange) {
        UUID userId = oidcUser.getUser().getId();
        return participantService
                .findByUserId(userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)))
                .flatMap(participant -> exchange.getFormData().flatMap(form -> {
                    String name = form.getFirst("name");
                    String description = form.getFirst("description");
                    List<UUID> skillIds = toUuidList(form.get("skillIds"));
                    return topicService
                            .propose(userId, name, description, skillIds)
                            .<Rendering>map(topic -> Rendering.redirectTo("/")
                                    .status(HttpStatus.SEE_OTHER)
                                    .build())
                            .onErrorResume(TopicConflictException.class, ex -> topicService
                                    .allSkills()
                                    .collectList()
                                    .map(allSkills -> Rendering.view("topics/form")
                                            .modelAttribute("error", ex.getMessage())
                                            .modelAttribute("name", name)
                                            .modelAttribute("description", description)
                                            .modelAttribute("allSkills", allSkills)
                                            .modelAttribute("selectedSkillIds", skillIds)
                                            .build()));
                }));
    }

    @GetMapping("/{id}/edit")
    public Mono<Rendering> editForm(@PathVariable UUID id, @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        UUID userId = oidcUser.getUser().getId();
        return topicService
                .findVisibleTo(id, userId, oidcUser.getUser().isOrganiser())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(topic -> {
                    failIfNotAuthor(topic, userId);
                    return Mono.zip(topicService.allSkills().collectList(), topicService.findDetail(id))
                            .map(tuple -> Rendering.view("topics/form")
                                    .modelAttribute("topicId", id)
                                    .modelAttribute("name", topic.getName())
                                    .modelAttribute("description", topic.getDescription())
                                    .modelAttribute("allSkills", tuple.getT1())
                                    .modelAttribute("selectedSkillIds", tuple.getT2().skillIds())
                                    .build());
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
                                .updateAsAuthor(id, userId, name, description, skillIds)
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

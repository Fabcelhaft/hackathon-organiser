package net.fabcelhaft.hackathonorganiser.topics;

import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantService;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.topic.Topic;
import net.fabcelhaft.hackathonorganiser.topic.TopicConflictException;
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

    public TopicSelfServiceController(TopicService topicService, ParticipantService participantService) {
        this.topicService = topicService;
        this.participantService = participantService;
    }

    @GetMapping("/new")
    public Mono<Rendering> newForm(@AuthenticationPrincipal HackathonOidcUser oidcUser) {
        return participantService
                .findByUserId(oidcUser.getUser().getId())
                .map(participant -> Rendering.view("topics/form").build())
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
                    return topicService
                            .propose(userId, name, description)
                            .<Rendering>map(topic -> Rendering.redirectTo("/")
                                    .status(HttpStatus.SEE_OTHER)
                                    .build())
                            .onErrorResume(
                                    TopicConflictException.class,
                                    ex -> Mono.just(Rendering.view("topics/form")
                                            .modelAttribute("error", ex.getMessage())
                                            .modelAttribute("name", name)
                                            .modelAttribute("description", description)
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
                    return Mono.just(Rendering.view("topics/form")
                            .modelAttribute("topicId", id)
                            .modelAttribute("name", topic.getName())
                            .modelAttribute("description", topic.getDescription())
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
                        return topicService
                                .updateAsAuthor(id, userId, name, description)
                                .<Rendering>map(saved -> Rendering.redirectTo("/")
                                        .status(HttpStatus.SEE_OTHER)
                                        .build())
                                .onErrorResume(
                                        TopicConflictException.class,
                                        ex -> Mono.just(Rendering.view("topics/form")
                                                .modelAttribute("error", ex.getMessage())
                                                .modelAttribute("topicId", id)
                                                .modelAttribute("name", name)
                                                .modelAttribute("description", description)
                                                .build()));
                    });
                });
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

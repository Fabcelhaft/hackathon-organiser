package net.fabcelhaft.hackathonorganiser.topics;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.group.GroupConflictException;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.topic.TopicJoinConflictException;
import net.fabcelhaft.hackathonorganiser.topic.TopicJoinService;
import net.fabcelhaft.hackathonorganiser.topic.TopicService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.reactive.result.view.Rendering;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * The self-service Join (Story 3; contracts/join-action.md) and Leave (Story 11;
 * contracts/topic-details.md) actions: both single-click, no confirmation step (FR-007a,
 * FR-037a). Delegates eligibility and the race-safe core entirely to {@link TopicJoinService};
 * this controller's only job is translating its outcomes into the redirect-with-flash shape every
 * other self-service action in this codebase already uses.
 */
@Controller
public class TopicJoinController {

    private final TopicJoinService topicJoinService;
    private final TopicService topicService;

    public TopicJoinController(TopicJoinService topicJoinService, TopicService topicService) {
        this.topicJoinService = topicJoinService;
        this.topicService = topicService;
    }

    @PostMapping("/topics/{id}/join")
    public Mono<Rendering> join(@PathVariable UUID id, @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        UUID userId = oidcUser.getUser().getId();
        return topicJoinService
                .join(id, userId)
                .flatMap(group -> topicService
                        .findById(id)
                        .map(topic -> redirectHomeWithFlash("You joined " + topic.getName() + "."))
                        .defaultIfEmpty(redirectHomeWithFlash("You joined the Topic.")))
                .onErrorResume(
                        TopicJoinConflictException.class, ex -> Mono.just(redirectHomeWithFlash(ex.getMessage())))
                .onErrorResume(
                        GroupConflictException.class, ex -> Mono.just(redirectHomeWithFlash(ex.getMessage())))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping("/topics/{id}/leave")
    public Mono<Rendering> leave(@PathVariable UUID id, @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        UUID userId = oidcUser.getUser().getId();
        return topicJoinService
                .leave(id, userId)
                .flatMap(group -> topicService
                        .findById(id)
                        .map(topic -> redirectToTopicWithFlash(id, "You left " + topic.getName() + "."))
                        .defaultIfEmpty(redirectToTopicWithFlash(id, "You left the Topic.")))
                .onErrorResume(TopicJoinConflictException.class, ex -> Mono.just(redirectToTopicWithFlash(
                        id, ex.getMessage())))
                .onErrorResume(GroupConflictException.class, ex -> Mono.just(redirectToTopicWithFlash(
                        id, ex.getMessage())));
    }

    private static Rendering redirectHomeWithFlash(String flash) {
        return Rendering.redirectTo("/?flash=" + URLEncoder.encode(flash, StandardCharsets.UTF_8))
                .status(HttpStatus.SEE_OTHER)
                .build();
    }

    private static Rendering redirectToTopicWithFlash(UUID topicId, String flash) {
        return Rendering.redirectTo(
                        "/topics/" + topicId + "?flash=" + URLEncoder.encode(flash, StandardCharsets.UTF_8))
                .status(HttpStatus.SEE_OTHER)
                .build();
    }
}

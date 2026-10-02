package net.fabcelhaft.hackathonorganiser.topics;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.audit.AuditActor;
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
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The self-service Join (Story 3; contracts/join-action.md) and Leave (Story 11;
 * contracts/topic-details.md) actions: both single-click, no confirmation step (FR-007a,
 * FR-037a). Delegates eligibility and the race-safe core entirely to {@link TopicJoinService};
 * this controller's only job is translating its outcomes into the redirect-with-flash shape every
 * other self-service action in this codebase already uses.
 *
 * <p><b>Feature 013 (FR-011a, FR-011b):</b> Join now returns the participant to the screen they
 * acted from, via the same allow-listed {@code redirect} field the upvote actions use
 * ({@link SelfServiceRedirects}). Before 013 it always returned to the Home Page, so joining from
 * the Topic Overview silently moved the participant to a different page — tolerable while every
 * action reloaded, but conspicuous beside a vote control that now preserves their place exactly.
 *
 * <p>Join deliberately remains a full page re-render rather than an in-place update (FR-011d).
 * Joining is not a row-local change: it alters the participant's own status, moves the Topic into
 * their pinned section, changes a member count, and — since a participant may belong to only one
 * Group — makes every other row on the page non-joinable. Updating one cell would leave the rest
 * of the screen asserting things that are no longer true.
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
    public Mono<Rendering> join(
            @PathVariable UUID id, @AuthenticationPrincipal HackathonOidcUser oidcUser, ServerWebExchange exchange) {
        UUID userId = oidcUser.getUser().getId();
        return SelfServiceRedirects.submittedValue(exchange)
                .map(SelfServiceRedirects::safe)
                .flatMap(target -> joinAndReturnTo(id, userId, target));
    }

    /**
     * Both the success and the two conflict outcomes land the participant back on {@code target},
     * which is the point: a Topic that filled up while they were reading is the case where being
     * moved to another page is most disorienting.
     */
    private Mono<Rendering> joinAndReturnTo(UUID id, UUID userId, String target) {
        return topicJoinService
                .join(id, userId, new AuditActor(userId, false))
                .flatMap(group -> topicService
                        .findById(id)
                        .map(topic -> redirectWithFlash(target, "You joined " + topic.getName() + "."))
                        .defaultIfEmpty(redirectWithFlash(target, "You joined the Topic.")))
                .onErrorResume(TopicJoinConflictException.class,
                        ex -> Mono.just(redirectWithFlash(target, ex.getMessage())))
                .onErrorResume(GroupConflictException.class,
                        ex -> Mono.just(redirectWithFlash(target, ex.getMessage())))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping("/topics/{id}/leave")
    public Mono<Rendering> leave(@PathVariable UUID id, @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        UUID userId = oidcUser.getUser().getId();
        return topicJoinService
                .leave(id, userId, new AuditActor(userId, false))
                .flatMap(group -> topicService
                        .findById(id)
                        .map(topic -> redirectToTopicWithFlash(id, "You left " + topic.getName() + "."))
                        .defaultIfEmpty(redirectToTopicWithFlash(id, "You left the Topic.")))
                .onErrorResume(TopicJoinConflictException.class, ex -> Mono.just(redirectToTopicWithFlash(
                        id, ex.getMessage())))
                .onErrorResume(GroupConflictException.class, ex -> Mono.just(redirectToTopicWithFlash(
                        id, ex.getMessage())));
    }

    /**
     * {@code target} has already passed {@link SelfServiceRedirects#safe}, so it is one of a fixed
     * set of in-app paths — never attacker-controlled text. The separator accounts for the Topic
     * detail path, which carries no query string of its own either, but keeps this correct if an
     * allow-listed target ever does.
     */
    private static Rendering redirectWithFlash(String target, String flash) {
        String separator = target.contains("?") ? "&" : "?";
        return Rendering.redirectTo(
                        target + separator + "flash=" + URLEncoder.encode(flash, StandardCharsets.UTF_8))
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

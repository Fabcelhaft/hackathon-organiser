package net.fabcelhaft.hackathonorganiser.topics;

import java.util.Set;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.topic.Topic;
import net.fabcelhaft.hackathonorganiser.topic.TopicService;
import net.fabcelhaft.hackathonorganiser.topic.TopicUpvoteService;
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
 * Self-service "Upvote"/"Withdraw upvote" actions (Story 1; contracts/topic-upvote-action.md),
 * mirroring {@link TopicJoinController}'s single-click, redirect-with-no-confirmation shape.
 * Rendered as a control on every row wherever a Topic is listed — Home Page, Topic Overview, Topic
 * Details — so this controller redirects back to whichever of those three pages the form was
 * submitted from, via the {@code redirect} hidden field each of those templates sets to its own
 * canonical path. That value is validated against a small allow-list (never reflected verbatim)
 * before being used as a redirect target, so a forged value cannot turn this into an open redirect.
 *
 * <p><b>Feature 013 — the fragment branch (contracts/vote-control-fragment.md):</b> when the request
 * carries {@code X-Vote-Fragment: true} both routes return the re-rendered vote control for that one
 * Topic instead of a redirect, so {@code static/js/topic-vote.js} can reconcile the optimistic
 * change against what the server actually holds without reloading the page (FR-006a).
 *
 * <p>A Thymeleaf fragment, not JSON, is deliberate. Constitution Principle III requires dynamic
 * content to be driven by Thymeleaf and forbids client-side rendering; returning JSON would push
 * markup assembly into the browser and duplicate the state-to-markup mapping in two languages.
 * Returning the rendered control keeps every byte of HTML server-made — the script only copies
 * values out of it.
 *
 * <p>The branch deliberately lives inside these existing routes rather than in new ones, so the
 * gate below (feature toggle, Topic visibility) stays the single place those rules are enforced.
 */
@Controller
public class TopicUpvoteController {

    /** Selects the fragment branch; absent means the pre-013 redirect behaviour (FR-006b). */
    static final String VOTE_FRAGMENT_HEADER = "X-Vote-Fragment";

    private final TopicUpvoteService topicUpvoteService;
    private final TopicService topicService;
    private final OrganiserSettingsService organiserSettingsService;

    public TopicUpvoteController(
            TopicUpvoteService topicUpvoteService,
            TopicService topicService,
            OrganiserSettingsService organiserSettingsService) {
        this.topicUpvoteService = topicUpvoteService;
        this.topicService = topicService;
        this.organiserSettingsService = organiserSettingsService;
    }

    @PostMapping("/topics/{id}/upvote")
    public Mono<Rendering> upvote(
            @PathVariable UUID id, @AuthenticationPrincipal HackathonOidcUser oidcUser, ServerWebExchange exchange) {
        UUID userId = oidcUser.getUser().getId();
        return gate(id, userId, oidcUser.getUser().isOrganiser())
                .then(topicUpvoteService.upvote(id, userId))
                .then(respond(id, userId, exchange));
    }

    @PostMapping("/topics/{id}/unupvote")
    public Mono<Rendering> unupvote(
            @PathVariable UUID id, @AuthenticationPrincipal HackathonOidcUser oidcUser, ServerWebExchange exchange) {
        UUID userId = oidcUser.getUser().getId();
        return gate(id, userId, oidcUser.getUser().isOrganiser())
                .then(topicUpvoteService.withdraw(id, userId))
                .then(respond(id, userId, exchange));
    }

    /**
     * The one place the two response shapes diverge (FR-006a, FR-006b). Both routes do identical
     * work and then come here: a caller that asked for a fragment gets the re-rendered control, and
     * everyone else — including every no-JavaScript form submission — gets exactly the 303 redirect
     * that shipped before feature 013.
     */
    private Mono<Rendering> respond(UUID topicId, UUID viewerUserId, ServerWebExchange exchange) {
        if (wantsFragment(exchange)) {
            return SelfServiceRedirects.submittedValue(exchange)
                    .flatMap(redirect -> renderControlFragment(topicId, viewerUserId, SelfServiceRedirects.safe(redirect)));
        }
        return SelfServiceRedirects.submittedValue(exchange).map(TopicUpvoteController::redirectTo);
    }

    private static boolean wantsFragment(ServerWebExchange exchange) {
        return "true".equalsIgnoreCase(exchange.getRequest().getHeaders().getFirst(VOTE_FRAGMENT_HEADER));
    }

    /**
     * Re-reads the Topic's authoritative post-action state and renders the shared control fragment
     * for it. Re-reading rather than computing the new value in the caller is the point: the client
     * applied an optimistic change, and this response is what it reconciles against, so it has to
     * reflect what the database holds — including when a concurrent action already changed it.
     *
     * <p>This branch never redirects, so the submitted redirect value is not a redirect target
     * here — but it is echoed back into the re-rendered form as the no-JavaScript fallback target
     * for the NEXT submission, so it goes through the same allow-list first. The enhancement does
     * not read that field (it copies only the four contract values), so this matters only if the
     * fragment is ever swapped wholesale; validating it now means that change cannot quietly
     * introduce an open redirect later.
     */
    private Mono<Rendering> renderControlFragment(UUID topicId, UUID viewerUserId, String redirect) {
        return topicService
                .findById(topicId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(topic -> Mono.zip(
                                topicUpvoteService.countsFor(Set.of(topicId)),
                                topicUpvoteService.viewerUpvotedTopicIds(Set.of(topicId), viewerUserId))
                        .map(state -> renderingFor(topic, state.getT1().getOrDefault(topicId, 0),
                                state.getT2().contains(topicId), redirect)));
    }

    private static Rendering renderingFor(
            Topic topic, int upvoteCount, boolean viewerHasUpvoted, String redirect) {
        return Rendering.view("fragments/topic-vote :: controlFromModel")
                .modelAttribute("topicId", topic.getId())
                .modelAttribute("topicName", topic.getName())
                .modelAttribute("upvoteCount", upvoteCount)
                .modelAttribute("viewerHasUpvoted", viewerHasUpvoted)
                .modelAttribute("redirect", redirect)
                .status(HttpStatus.OK)
                .build();
    }

    /**
     * Both routes share the same gate (contracts/topic-upvote-action.md): the feature toggle
     * (404 if off — the control isn't rendered anywhere while it's off either) and Topic visibility
     * (reusing {@link TopicService#findVisibleTo}'s existing Pending-visibility rule; 404 for an
     * unknown or invisible Topic).
     */
    private Mono<Void> gate(UUID topicId, UUID viewerUserId, boolean viewerIsOrganiser) {
        return organiserSettingsService.current().flatMap(settings -> {
            if (!settings.isTopicUpvotingEnabled()) {
                return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
            }
            return topicService
                    .findVisibleTo(topicId, viewerUserId, viewerIsOrganiser)
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                    .then();
        });
    }

    private static Rendering redirectTo(String redirect) {
        return Rendering.redirectTo(SelfServiceRedirects.safe(redirect)).status(HttpStatus.SEE_OTHER).build();
    }

}

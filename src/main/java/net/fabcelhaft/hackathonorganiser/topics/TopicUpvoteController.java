package net.fabcelhaft.hackathonorganiser.topics;

import java.util.UUID;
import java.util.regex.Pattern;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
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
 */
@Controller
public class TopicUpvoteController {

    private static final Pattern TOPIC_DETAIL_PATH = Pattern.compile("^/topics/[0-9a-fA-F-]{36}$");

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
                .then(redirectFormValue(exchange))
                .map(TopicUpvoteController::redirectTo);
    }

    @PostMapping("/topics/{id}/unupvote")
    public Mono<Rendering> unupvote(
            @PathVariable UUID id, @AuthenticationPrincipal HackathonOidcUser oidcUser, ServerWebExchange exchange) {
        UUID userId = oidcUser.getUser().getId();
        return gate(id, userId, oidcUser.getUser().isOrganiser())
                .then(topicUpvoteService.withdraw(id, userId))
                .then(redirectFormValue(exchange))
                .map(TopicUpvoteController::redirectTo);
    }

    private static Mono<String> redirectFormValue(ServerWebExchange exchange) {
        return exchange.getFormData().map(form -> {
            String redirect = form.getFirst("redirect");
            return redirect == null ? "" : redirect;
        });
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
        String target = isAllowedRedirect(redirect) ? redirect : "/";
        return Rendering.redirectTo(target).status(HttpStatus.SEE_OTHER).build();
    }

    private static boolean isAllowedRedirect(String redirect) {
        return redirect != null
                && ("/".equals(redirect)
                        || "/topics/overview".equals(redirect)
                        || TOPIC_DETAIL_PATH.matcher(redirect).matches());
    }
}

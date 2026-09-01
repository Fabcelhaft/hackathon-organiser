package net.fabcelhaft.hackathonorganiser.topics;

import java.util.Optional;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettings;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantService;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.topic.TopicDiscoveryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.reactive.result.view.Rendering;
import reactor.core.publisher.Mono;

/**
 * The full Topic Overview (Story 5, Story 9, Story 10; contracts/home-and-topic-overview.md): every
 * Topic visible to the caller (reusing {@code TopicService}'s existing Pending-visibility rule),
 * with author, participant count, needed Skills, and Compliance status, the viewer's own Topics
 * pinned above the rest (FR-034), and — on each joinable row — the same self-service "Join" action
 * the Home Page already offers (FR-006a). Available to every authenticated user — no Organiser
 * check, no configurable audience, unlike the Participants directory — so this route lives in the
 * self-service {@code topics} package alongside {@link TopicJoinController}, not under
 * {@code /organiser/**}.
 */
@Controller
public class TopicOverviewController {

    private final TopicDiscoveryService topicDiscoveryService;
    private final ParticipantService participantService;
    private final GroupService groupService;
    private final OrganiserSettingsService organiserSettingsService;

    public TopicOverviewController(
            TopicDiscoveryService topicDiscoveryService,
            ParticipantService participantService,
            GroupService groupService,
            OrganiserSettingsService organiserSettingsService) {
        this.topicDiscoveryService = topicDiscoveryService;
        this.participantService = participantService;
        this.groupService = groupService;
        this.organiserSettingsService = organiserSettingsService;
    }

    @GetMapping("/topics/overview")
    public Mono<Rendering> overview(@AuthenticationPrincipal HackathonOidcUser oidcUser) {
        UUID userId = oidcUser.getUser().getId();
        boolean isOrganiser = oidcUser.getUser().isOrganiser();
        return Mono.zip(
                        topicDiscoveryService.findTopicOverview(userId, isOrganiser).collectList(),
                        viewerCanJoinTopics(userId),
                        organiserSettingsService.current())
                .map(tuple -> Rendering.view("topics/overview")
                        .modelAttribute("rows", tuple.getT1())
                        .modelAttribute("canJoinTopics", tuple.getT2())
                        .modelAttribute(
                                "complianceVisible",
                                isOrganiser || tuple.getT3().isComplianceVisibleToParticipants())
                        .build());
    }

    /**
     * The same viewer-side join eligibility {@code HomeController} already computes (FR-006a):
     * Active Participant, not already in an active Group, and Topic joining currently enabled —
     * independent of any individual row's own {@code joinable} flag (Topic-side approval/capacity),
     * which the template combines with this.
     */
    private Mono<Boolean> viewerCanJoinTopics(UUID userId) {
        return Mono.zip(
                        participantService.findByUserId(userId).map(Optional::of).defaultIfEmpty(Optional.empty()),
                        organiserSettingsService.current())
                .flatMap(tuple -> {
                    Optional<Participant> participantOpt = tuple.getT1();
                    OrganiserSettings settings = tuple.getT2();
                    boolean viewerIsActiveParticipant = participantOpt
                            .map(p -> p.getStatus() == ParticipantStatus.ACTIVE)
                            .orElse(false);
                    if (!viewerIsActiveParticipant || !settings.isTopicJoiningEnabled()) {
                        return Mono.just(false);
                    }
                    return groupService
                            .findActiveGroupForParticipant(participantOpt.get().getId())
                            .hasElement()
                            .map(hasActiveGroup -> !hasActiveGroup);
                });
    }
}

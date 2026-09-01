package net.fabcelhaft.hackathonorganiser.home;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.content.ContentPageService;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettings;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantConflictException;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantService;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.topic.Topic;
import net.fabcelhaft.hackathonorganiser.topic.TopicDiscoveryService;
import net.fabcelhaft.hackathonorganiser.topic.TopicService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.reactive.result.view.Rendering;
import reactor.core.publisher.Mono;

/**
 * The authenticated homepage (T015; contracts/registration-and-status.md): the left column shows
 * the current user's Participant status, assigned Group/Topic if any, and a working
 * Register-or-Revoke action gated by the current {@link OrganiserSettings}; the right column
 * renders the Organiser-designated Content Page (a static placeholder until US5 wires up
 * {@code ContentPageService}). Every route here requires only plain authentication — no Organiser
 * role — per {@code SecurityConfig}'s default {@code .anyExchange().authenticated()} rule.
 *
 * <p>Flash confirmations (FR-033) travel across the POST -> redirect -> GET boundary as a
 * {@code flash} query parameter rather than a session-based flash-attribute mechanism: Constitution
 * III's server-rendered, no-hidden-state model favors a value visible directly in the redirect
 * URL over introducing session state this codebase has never needed before.
 */
@Controller
public class HomeController {

    private static final int HOME_PAGE_TOPIC_LIMIT = 10;

    private final ParticipantService participantService;
    private final OrganiserSettingsService organiserSettingsService;
    private final GroupService groupService;
    private final TopicService topicService;
    private final TopicDiscoveryService topicDiscoveryService;
    private final ContentPageService contentPageService;

    public HomeController(
            ParticipantService participantService,
            OrganiserSettingsService organiserSettingsService,
            GroupService groupService,
            TopicService topicService,
            TopicDiscoveryService topicDiscoveryService,
            ContentPageService contentPageService) {
        this.participantService = participantService;
        this.organiserSettingsService = organiserSettingsService;
        this.groupService = groupService;
        this.topicService = topicService;
        this.topicDiscoveryService = topicDiscoveryService;
        this.contentPageService = contentPageService;
    }

    @GetMapping("/")
    public Mono<Rendering> home(
            @AuthenticationPrincipal HackathonOidcUser oidcUser,
            @RequestParam(name = "flash", required = false) String flash) {
        UUID userId = oidcUser.getUser().getId();
        return Mono.zip(
                        participantService.findByUserId(userId).map(Optional::of).defaultIfEmpty(Optional.empty()),
                        organiserSettingsService.current())
                .flatMap(tuple -> {
                    Optional<Participant> participantOpt = tuple.getT1();
                    OrganiserSettings settings = tuple.getT2();
                    boolean notParticipated = participantOpt
                            .map(p -> p.getStatus() == ParticipantStatus.NOT_PARTICIPATED)
                            .orElse(false);
                    boolean canRegister = !notParticipated
                            && settings.isSelfRegistrationEnabled()
                            && participantOpt
                                    .map(p -> p.getStatus() != ParticipantStatus.ACTIVE)
                                    .orElse(true);
                    boolean canRevoke = !notParticipated
                            && settings.isSelfRevocationEnabled()
                            && participantOpt
                                    .map(p -> p.getStatus() == ParticipantStatus.ACTIVE)
                                    .orElse(false);
                    UUID viewerParticipantId =
                            participantOpt.map(Participant::getId).orElse(null);
                    boolean viewerIsActiveParticipant = participantOpt
                            .map(p -> p.getStatus() == ParticipantStatus.ACTIVE)
                            .orElse(false);
                    return Mono.zip(
                                    assignedGroupAndTopic(participantOpt),
                                    topicDiscoveryService
                                            .findOpenTopicsForHomePage(
                                                    userId, viewerParticipantId, HOME_PAGE_TOPIC_LIMIT)
                                            .collectList(),
                                    contentPageService
                                            .findRenderedHomepage()
                                            .map(Optional::of)
                                            .defaultIfEmpty(Optional.empty()))
                            .map(results -> {
                                boolean canJoinTopics = viewerIsActiveParticipant
                                        && settings.isTopicJoiningEnabled()
                                        && results.getT1().group() == null;
                                return Rendering.view("home/index")
                                        .modelAttribute("flash", flash)
                                        .modelAttribute("participant", participantOpt.orElse(null))
                                        .modelAttribute("canRegister", canRegister)
                                        .modelAttribute("canRevoke", canRevoke)
                                        .modelAttribute("notParticipated", notParticipated)
                                        .modelAttribute("assignedGroup", results.getT1().group())
                                        .modelAttribute("assignedTopic", results.getT1().topic())
                                        .modelAttribute("openTopics", results.getT2())
                                        .modelAttribute("canJoinTopics", canJoinTopics)
                                        .modelAttribute("currentUserId", userId)
                                        .modelAttribute("canProposeTopic", participantOpt.isPresent())
                                        .modelAttribute(
                                                "homepageContent", results.getT3().orElse(null))
                                        .build();
                            });
                });
    }

    // POST /register no longer lives here (FR-001): registration is now form-driven via
    // RegistrationController's GET/POST /register, superseding 003's immediate-registration button.

    @PostMapping("/revoke")
    public Mono<Rendering> revoke(@AuthenticationPrincipal HackathonOidcUser oidcUser) {
        UUID userId = oidcUser.getUser().getId();
        return participantService
                .findByUserId(userId)
                .flatMap(participant -> participantService
                        .selfRevoke(participant.getId())
                        .map(p -> redirectHomeWithFlash("Registration revoked."))
                        .onErrorResume(
                                ParticipantConflictException.class,
                                ex -> Mono.just(redirectHomeWithFlash(ex.getMessage()))))
                .switchIfEmpty(Mono.just(redirectHomeWithFlash("You have no registration to revoke.")));
    }

    private Mono<GroupAndTopic> assignedGroupAndTopic(Optional<Participant> participantOpt) {
        if (participantOpt.isEmpty()) {
            return Mono.just(new GroupAndTopic(null, null));
        }
        return groupService
                .findActiveGroupForParticipant(participantOpt.get().getId())
                .flatMap(group -> topicService
                        .findById(group.getTopicId())
                        .map(topic -> new GroupAndTopic(group, topic))
                        .defaultIfEmpty(new GroupAndTopic(group, null)))
                .defaultIfEmpty(new GroupAndTopic(null, null));
    }

    private static Rendering redirectHomeWithFlash(String flash) {
        return Rendering.redirectTo("/?flash=" + URLEncoder.encode(flash, StandardCharsets.UTF_8))
                .status(HttpStatus.SEE_OTHER)
                .build();
    }

    /** The homepage's assigned-Group-and-Topic read model — both null if the Participant has neither. */
    private record GroupAndTopic(Group group, Topic topic) {}
}

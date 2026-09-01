package net.fabcelhaft.hackathonorganiser.participants;

import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.customfield.IsoCountryCatalog;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantService;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.reactive.result.view.Rendering;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * The Participants directory table and detail view (T046; contracts/participants-directory.md):
 * {@code GET /participants}, {@code GET /participants/{id}}. Access is gated by the *configurable*
 * audience setting via {@link ParticipantsDirectoryAccessPolicy}, not a fixed {@code
 * SecurityConfig} role rule (research.md §6) — both routes return 403 when the requester is
 * outside the configured audience and the requested id isn't their own.
 */
@Controller
public class ParticipantsDirectoryController {

    private final ParticipantService participantService;
    private final OrganiserSettingsService organiserSettingsService;
    private final ParticipantsDirectoryAccessPolicy accessPolicy;

    public ParticipantsDirectoryController(
            ParticipantService participantService,
            OrganiserSettingsService organiserSettingsService,
            ParticipantsDirectoryAccessPolicy accessPolicy) {
        this.participantService = participantService;
        this.organiserSettingsService = organiserSettingsService;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping("/participants")
    public Mono<Rendering> list(@AuthenticationPrincipal HackathonOidcUser oidcUser) {
        UUID userId = oidcUser.getUser().getId();
        boolean isOrganiser = oidcUser.getUser().isOrganiser();
        return Mono.zip(organiserSettingsService.current(), participantService.findByUserId(userId).hasElement())
                .flatMap(tuple -> {
                    if (!accessPolicy.isInAudience(tuple.getT1(), isOrganiser, tuple.getT2())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
                    }
                    return Mono.just(Rendering.view("participants/list")
                            .modelAttribute("rows", participantService.findDirectoryListing().collectList())
                            .modelAttribute(
                                    "countryNamesByCode",
                                    IsoCountryCatalog.all().stream()
                                            .collect(java.util.stream.Collectors.toMap(
                                                    IsoCountryCatalog.Country::code, IsoCountryCatalog.Country::name)))
                            .build());
                });
    }

    @GetMapping("/participants/{id}")
    public Mono<Rendering> detail(@AuthenticationPrincipal HackathonOidcUser oidcUser, @PathVariable UUID id) {
        UUID userId = oidcUser.getUser().getId();
        boolean isOrganiser = oidcUser.getUser().isOrganiser();
        return renderDetail(id, userId, isOrganiser);
    }

    /** Shared by {@code ProfileController#profile} so there is exactly one detail-rendering path. */
    Mono<Rendering> renderDetail(UUID participantId, UUID viewerUserId, boolean viewerIsOrganiser) {
        return participantService
                .findDetailForViewer(participantId, viewerUserId, viewerIsOrganiser)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(detail -> {
                    if (detail.self() || viewerIsOrganiser) {
                        return renderDetailView(detail);
                    }
                    return Mono.zip(
                                    organiserSettingsService.current(),
                                    participantService.findByUserId(viewerUserId).hasElement())
                            .flatMap(tuple -> {
                                if (!accessPolicy.isInAudience(tuple.getT1(), false, tuple.getT2())) {
                                    return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
                                }
                                return renderDetailView(detail);
                            });
                });
    }

    private Mono<Rendering> renderDetailView(net.fabcelhaft.hackathonorganiser.participant.ParticipantService.ParticipantViewerDetail detail) {
        return organiserSettingsService.current().map(settings -> Rendering.view("participants/detail")
                .modelAttribute("mode", detail.self() ? "self" : (detail.organiserView() ? "organiser" : "other"))
                .modelAttribute("viewerDetail", detail)
                .modelAttribute("canEdit", detail.self() && settings.isSelfEditEnabled())
                .modelAttribute("teamsLinksEnabled", settings.isTeamsLinksEnabled())
                .modelAttribute(
                        "countryNamesByCode",
                        IsoCountryCatalog.all().stream()
                                .collect(java.util.stream.Collectors.toMap(
                                        IsoCountryCatalog.Country::code, IsoCountryCatalog.Country::name)))
                .build());
    }
}

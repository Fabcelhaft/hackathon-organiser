package net.fabcelhaft.hackathonorganiser.organiser.participant;

import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.audit.AuditActor;
import net.fabcelhaft.hackathonorganiser.audit.AuditService;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantConflictException;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantService;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
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
 * Organiser-only views for Participant registration, status, Skill selections, and Custom Field
 * values (T041; contracts/participant-management.md). Access to every route here is restricted to
 * {@code ROLE_ORGANISER} by {@code SecurityConfig}'s {@code /organiser/**} path rule (FR-022).
 */
@Controller
@RequestMapping("/organiser/participants")
public class ParticipantController {

    private final ParticipantService participantService;
    private final OrganiserSettingsService organiserSettingsService;
    private final AuditService auditService;

    public ParticipantController(
            ParticipantService participantService,
            OrganiserSettingsService organiserSettingsService,
            AuditService auditService) {
        this.participantService = participantService;
        this.organiserSettingsService = organiserSettingsService;
        this.auditService = auditService;
    }

    @GetMapping
    public Mono<Rendering> list() {
        return organiserSettingsService
                .current()
                .map(settings -> Rendering.view("organiser/participants/list")
                        .modelAttribute("participants", participantService.findAllSummaries())
                        .modelAttribute("teamsLinksEnabled", settings.isTeamsLinksEnabled())
                        .build());
    }

    @GetMapping("/new")
    public Mono<Rendering> newForm() {
        return Mono.just(Rendering.view("organiser/participants/new")
                .modelAttribute("availableUsers", participantService.findUsersWithoutParticipant())
                .build());
    }

    @PostMapping
    public Mono<Rendering> create(ServerWebExchange exchange, @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        // WebFlux's @RequestParam only ever reads URL query parameters, never a form-urlencoded
        // request body (unlike Spring MVC) — so form fields are read via ServerWebExchange.getFormData().
        return exchange.getFormData().flatMap(form -> {
            String userIdRaw = form.getFirst("user_id");
            AuditActor actor = new AuditActor(oidcUser.getUser().getId(), true);
            return Mono.fromCallable(() -> parseUuid(userIdRaw))
                    .flatMap(userId -> participantService.register(userId, actor))
                    .<Rendering>map(participant -> Rendering.redirectTo(
                                    "/organiser/participants/" + participant.getId())
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(
                            ParticipantConflictException.class,
                            ex -> Mono.just(Rendering.view("organiser/participants/new")
                                    .modelAttribute("error", ex.getMessage())
                                    .modelAttribute(
                                            "availableUsers", participantService.findUsersWithoutParticipant())
                                    .build()));
        });
    }

    @GetMapping("/{id}")
    public Mono<Rendering> detail(@PathVariable UUID id) {
        return renderDetail(id, null);
    }

    /**
     * A Participant's full audit history, most-recent-first (Story 2; contracts/audit-
     * retrieval.md; FR-007, FR-008, FR-011). Unknown {@code id} -> 404.
     */
    @GetMapping("/{id}/audit")
    public Mono<Rendering> audit(@PathVariable UUID id) {
        return participantService
                .findDetail(id)
                .map(detail -> Rendering.view("organiser/participants/audit")
                        .modelAttribute("entries", auditService.findForParticipant(id))
                        .build())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping("/{id}/status")
    public Mono<Rendering> changeStatus(
            @PathVariable UUID id, ServerWebExchange exchange, @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        return exchange.getFormData().flatMap(form -> {
            ParticipantStatus status = ParticipantStatus.valueOf(form.getFirst("status"));
            return participantService
                    .changeStatus(id, status, new AuditActor(oidcUser.getUser().getId(), true))
                    .<Rendering>map(participant -> Rendering.redirectTo("/organiser/participants/" + id)
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
        });
    }

    @PostMapping("/{id}/skills")
    public Mono<Rendering> replaceSkills(
            @PathVariable UUID id, ServerWebExchange exchange, @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        return exchange.getFormData().flatMap(form -> {
            List<UUID> skillIds = toUuidList(form.get("skill_ids"));
            return participantService
                    .replaceSkills(id, skillIds, new AuditActor(oidcUser.getUser().getId(), true))
                    .<Rendering>map(participant -> Rendering.redirectTo("/organiser/participants/" + id)
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
        });
    }

    @PostMapping("/{id}/delete")
    public Mono<Rendering> delete(@PathVariable UUID id, @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        return participantService
                .delete(id, new AuditActor(oidcUser.getUser().getId(), true))
                .then(Mono.just(Rendering.redirectTo("/organiser/participants")
                        .status(HttpStatus.SEE_OTHER)
                        .build()))
                .onErrorResume(
                        ParticipantConflictException.class,
                        ex -> organiserSettingsService
                                .current()
                                .map(settings -> Rendering.view("organiser/participants/list")
                                        .modelAttribute("participants", participantService.findAllSummaries())
                                        .modelAttribute("teamsLinksEnabled", settings.isTeamsLinksEnabled())
                                        .modelAttribute("error", ex.getMessage())
                                        .status(HttpStatus.CONFLICT)
                                        .build()));
    }

    @PostMapping("/{id}/custom-fields/{fieldId}")
    public Mono<Rendering> setCustomFieldValue(
            @PathVariable UUID id,
            @PathVariable UUID fieldId,
            ServerWebExchange exchange,
            @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        return exchange.getFormData().flatMap(form -> {
            String value = form.getFirst("value");
            List<UUID> optionIds = toUuidList(form.get("option_ids"));
            return participantService
                    .setCustomFieldValue(id, fieldId, value, optionIds, new AuditActor(oidcUser.getUser().getId(), true))
                    .<Rendering>map(participant -> Rendering.redirectTo("/organiser/participants/" + id)
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(ParticipantConflictException.class, ex -> renderDetail(id, ex.getMessage()))
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
        });
    }

    private Mono<Rendering> renderDetail(UUID id, String error) {
        return Mono.zip(participantService.findDetail(id), organiserSettingsService.current())
                .map(tuple -> {
                    Rendering.Builder<?> builder = Rendering.view("organiser/participants/detail")
                            .modelAttribute("detail", tuple.getT1())
                            .modelAttribute("allSkills", participantService.allSkills())
                            .modelAttribute("statuses", ParticipantStatus.values())
                            .modelAttribute("teamsLinksEnabled", tuple.getT2().isTeamsLinksEnabled());
                    if (error != null) {
                        builder = builder.modelAttribute("error", error);
                    }
                    return builder.build();
                })
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ParticipantConflictException("Please select a user");
        }
    }

    private static List<UUID> toUuidList(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().filter(s -> s != null && !s.isBlank()).map(UUID::fromString).toList();
    }
}

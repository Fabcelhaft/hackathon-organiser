package net.fabcelhaft.hackathonorganiser.organiser.group;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.audit.AuditActor;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceService;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceStatus;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupConflictException;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
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
 * Organiser-only views for Group create/view, member add/remove, and disband (T057;
 * contracts/group-management.md). Access to every route here is restricted to {@code
 * ROLE_ORGANISER} by {@code SecurityConfig}'s {@code /organiser/**} path rule (FR-022).
 *
 * <p>Per the contract, an unknown {@code topic_id}/{@code id}/{@code participantId} always 404s;
 * a domain-invariant violation (Topic already has an active Group FR-016a, unknown/double-booked
 * Participant FR-017, adding a member to a {@code DISBANDED} Group, disbanding an already-{@code
 * DISBANDED} Group) instead re-renders the relevant form/detail view with a friendly error.
 */
@Controller
@RequestMapping("/organiser/groups")
public class GroupController {

    private final GroupService groupService;
    private final ComplianceService complianceService;

    public GroupController(GroupService groupService, ComplianceService complianceService) {
        this.groupService = groupService;
        this.complianceService = complianceService;
    }

    @GetMapping
    public Mono<Rendering> list() {
        return Mono.just(Rendering.view("organiser/groups/list")
                .modelAttribute("groups", groupService.findAllSummaries())
                .build());
    }

    @GetMapping("/new")
    public Mono<Rendering> newForm() {
        return Mono.just(newFormRendering(null));
    }

    @PostMapping
    public Mono<Rendering> create(ServerWebExchange exchange, @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        // WebFlux's @RequestParam only ever reads URL query parameters, never a form-urlencoded
        // request body (unlike Spring MVC) — so form fields are read via ServerWebExchange.getFormData().
        return exchange.getFormData().flatMap(form -> {
            UUID topicId = parseUuidOrNull(form.getFirst("topic_id"));
            List<UUID> participantIds = toUuidList(form.get("participant_ids"));
            return groupService
                    .create(topicId, participantIds, new AuditActor(oidcUser.getUser().getId(), true))
                    .<Rendering>map(group -> Rendering.redirectTo("/organiser/groups/" + group.getId())
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(
                            GroupConflictException.class,
                            ex -> Mono.just(newFormRendering(ex.getMessage())))
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
        });
    }

    @GetMapping("/{id}")
    public Mono<Rendering> detail(@PathVariable UUID id) {
        return renderDetail(id, null);
    }

    @PostMapping("/{id}/members")
    public Mono<Rendering> addMember(
            @PathVariable UUID id, ServerWebExchange exchange, @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        return exchange.getFormData().flatMap(form -> {
            UUID participantId = parseUuidOrNull(form.getFirst("participant_id"));
            return groupService
                    .addMember(id, participantId, new AuditActor(oidcUser.getUser().getId(), true))
                    .<Rendering>map(group -> Rendering.redirectTo("/organiser/groups/" + id)
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(GroupConflictException.class, ex -> renderDetail(id, ex.getMessage()))
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
        });
    }

    @PostMapping("/{id}/members/{participantId}/remove")
    public Mono<Rendering> removeMember(
            @PathVariable UUID id,
            @PathVariable UUID participantId,
            @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        return groupService
                .removeMember(id, participantId, new AuditActor(oidcUser.getUser().getId(), true))
                .<Rendering>map(group -> Rendering.redirectTo("/organiser/groups/" + id)
                        .status(HttpStatus.SEE_OTHER)
                        .build())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping("/{id}/compliance-override")
    public Mono<Rendering> setComplianceOverride(
            @PathVariable UUID id, ServerWebExchange exchange, @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        return exchange.getFormData().flatMap(form -> {
            boolean override = "true".equalsIgnoreCase(form.getFirst("override"));
            return groupService
                    .setComplianceOverride(id, override, new AuditActor(oidcUser.getUser().getId(), true))
                    .<Rendering>map(group -> Rendering.redirectTo("/organiser/groups/" + id + "?flash="
                                    + (override ? "Compliance+override+set." : "Compliance+override+removed."))
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
        });
    }

    @PostMapping("/{id}/disband")
    public Mono<Rendering> disband(@PathVariable UUID id, @AuthenticationPrincipal HackathonOidcUser oidcUser) {
        return groupService
                .disband(id, new AuditActor(oidcUser.getUser().getId(), true))
                .<Rendering>map(group -> Rendering.redirectTo("/organiser/groups/" + id)
                        .status(HttpStatus.SEE_OTHER)
                        .build())
                .onErrorResume(GroupConflictException.class, ex -> renderDetail(id, ex.getMessage()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    private Rendering newFormRendering(String error) {
        Rendering.Builder<?> builder = Rendering.view("organiser/groups/new")
                .modelAttribute("availableTopics", groupService.findTopicsWithoutActiveGroup())
                .modelAttribute("allParticipants", groupService.allParticipants());
        if (error != null) {
            builder = builder.modelAttribute("error", error);
        }
        return builder.build();
    }

    private Mono<Rendering> renderDetail(UUID id, String error) {
        return groupService
                .findDetail(id)
                .flatMap(detail -> complianceStatusFor(detail.group()).map(complianceStatus -> {
                    Rendering.Builder<?> builder = Rendering.view("organiser/groups/detail")
                            .modelAttribute("detail", detail)
                            .modelAttribute("complianceStatus", complianceStatus.orElse(null))
                            .modelAttribute("allParticipants", groupService.allParticipants());
                    if (error != null) {
                        builder = builder.modelAttribute("error", error);
                    }
                    return builder.build();
                }))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    /**
     * The Group detail view's Compliance badge (FR-014, FR-015): {@link Optional#empty()} renders
     * as "No Group Yet" — unreachable here since every {@code Group} row already exists, but the
     * same {@link ComplianceService#evaluate} contract as the Topic Overview (research.md §5) is
     * reused verbatim so the two views can never disagree.
     */
    private Mono<Optional<ComplianceStatus>> complianceStatusFor(Group group) {
        return groupService
                .activeMemberParticipantIds(group.getId())
                .flatMap(memberIds -> complianceService.evaluate(group, memberIds))
                .map(Optional::of);
    }

    private static UUID parseUuidOrNull(String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static List<UUID> toUuidList(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().filter(s -> s != null && !s.isBlank()).map(UUID::fromString).toList();
    }
}

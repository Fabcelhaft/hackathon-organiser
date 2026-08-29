package net.fabcelhaft.hackathonorganiser.web;

import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantService;
import net.fabcelhaft.hackathonorganiser.participants.ParticipantsDirectoryAccessPolicy;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import reactor.core.publisher.Mono;

/**
 * Injects {@code currentUser} (nullable — some pages render fine unauthenticated, but this keeps
 * the advice reusable), {@code isOrganiser} (boolean), and {@code showParticipantsMenuItem}
 * (boolean, FR-025) into every model for every annotated controller (research.md §7, FR-008). The
 * shared layout fragment's {@code th:if} attributes conditionally render the Organiser and
 * Participants nav links.
 *
 * <p>{@code showParticipantsMenuItem} is computed via {@link ParticipantsDirectoryAccessPolicy} —
 * the same single source of truth {@code ParticipantsDirectoryController} enforces access with, so
 * the visible menu item and the enforced access can never drift apart (research.md §6).
 *
 * <p>No {@code thymeleaf-extras-springsecurity6} dialect is on the classpath — that trade-off was
 * deliberately settled by 002's {@code SecurityConfig} (it's why CSRF is currently disabled) and
 * is not reopened here. This {@code @ControllerAdvice} + reactive {@code @ModelAttribute} gets the
 * same behavioral result with zero new dependencies.
 */
@ControllerAdvice
public class CurrentUserModelAdvice {

    private final OrganiserSettingsService organiserSettingsService;
    private final ParticipantService participantService;
    private final ParticipantsDirectoryAccessPolicy accessPolicy;

    public CurrentUserModelAdvice(
            OrganiserSettingsService organiserSettingsService,
            ParticipantService participantService,
            ParticipantsDirectoryAccessPolicy accessPolicy) {
        this.organiserSettingsService = organiserSettingsService;
        this.participantService = participantService;
        this.accessPolicy = accessPolicy;
    }

    @ModelAttribute
    public Mono<Void> populateCurrentUser(Model model) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Authentication::getPrincipal)
                .filter(HackathonOidcUser.class::isInstance)
                .cast(HackathonOidcUser.class)
                .flatMap(user -> {
                    model.addAttribute("currentUser", user);
                    boolean isOrganiser = user.getUser().isOrganiser();
                    model.addAttribute("isOrganiser", isOrganiser);
                    // .thenReturn(user), not .then(): a Mono<Void> always "completes empty" (no
                    // onNext), which would make the switchIfEmpty below fire even on a successful,
                    // fully-authenticated pass — it must only fire when the cast/filter above
                    // found no HackathonOidcUser at all.
                    return Mono.zip(
                                    organiserSettingsService.current(),
                                    participantService
                                            .findByUserId(user.getUser().getId())
                                            .hasElement())
                            .doOnNext(tuple -> model.addAttribute(
                                    "showParticipantsMenuItem",
                                    accessPolicy.isInAudience(tuple.getT1(), isOrganiser, tuple.getT2())))
                            .thenReturn(user);
                })
                .switchIfEmpty(Mono.fromRunnable(() -> {
                    model.addAttribute("isOrganiser", false);
                    model.addAttribute("showParticipantsMenuItem", false);
                }))
                .then();
    }
}

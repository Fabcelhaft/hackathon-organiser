package net.fabcelhaft.hackathonorganiser.web;

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
 * the advice reusable) and {@code isOrganiser} (boolean) into every model for every annotated
 * controller (research.md §7, FR-008). The shared layout fragment's {@code th:if="${isOrganiser}"}
 * conditionally renders the Organiser nav link.
 *
 * <p>No {@code thymeleaf-extras-springsecurity6} dialect is on the classpath — that trade-off was
 * deliberately settled by 002's {@code SecurityConfig} (it's why CSRF is currently disabled) and
 * is not reopened here. This {@code @ControllerAdvice} + reactive {@code @ModelAttribute} gets the
 * same behavioral result with zero new dependencies.
 */
@ControllerAdvice
public class CurrentUserModelAdvice {

    @ModelAttribute
    public Mono<Void> populateCurrentUser(Model model) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Authentication::getPrincipal)
                .filter(HackathonOidcUser.class::isInstance)
                .cast(HackathonOidcUser.class)
                .doOnNext(user -> {
                    model.addAttribute("currentUser", user);
                    model.addAttribute("isOrganiser", user.getUser().isOrganiser());
                })
                .switchIfEmpty(Mono.fromRunnable(() -> model.addAttribute("isOrganiser", false)))
                .then();
    }
}

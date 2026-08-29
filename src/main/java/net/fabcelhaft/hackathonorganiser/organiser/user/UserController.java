package net.fabcelhaft.hackathonorganiser.organiser.user;

import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.user.UserService;
import org.springframework.http.HttpStatus;
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
 * Organiser-only views for listing, viewing, and toggling the Organiser privilege on User records
 * (T016; contracts/user-management.md). Access to every route here is restricted to
 * {@code ROLE_ORGANISER} by {@code SecurityConfig}'s {@code /organiser/**} path rule (FR-022).
 */
@Controller
@RequestMapping("/organiser/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public Mono<Rendering> list() {
        return Mono.just(Rendering.view("organiser/users/list")
                .modelAttribute("users", userService.findAll())
                .build());
    }

    @GetMapping("/{id}")
    public Mono<Rendering> detail(@PathVariable UUID id) {
        return userService.findById(id)
                .map(user -> Rendering.view("organiser/users/detail")
                        .modelAttribute("user", user)
                        .build())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping("/{id}/organiser")
    public Mono<Rendering> toggleOrganiser(@PathVariable UUID id, ServerWebExchange exchange) {
        // Unlike Spring MVC's @RequestParam, WebFlux's @RequestParam only ever reads URL query
        // parameters, never a form-urlencoded request body — so the "organiser=true|false" form
        // field from contracts/user-management.md is read via ServerWebExchange.getFormData().
        return exchange.getFormData()
                .map(form -> Boolean.parseBoolean(form.getFirst("organiser")))
                .flatMap(organiser -> userService.setOrganiser(id, organiser))
                .map(user -> Rendering.redirectTo("/organiser/users/" + id)
                        .status(HttpStatus.SEE_OTHER)
                        .build())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }
}

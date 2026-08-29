package net.fabcelhaft.hackathonorganiser.organiser.skill;

import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.skill.SkillConflictException;
import net.fabcelhaft.hackathonorganiser.skill.SkillService;
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
 * Organiser-only views for the Skill catalog (T030; contracts/catalog-management.md). Access to
 * every route here is restricted to {@code ROLE_ORGANISER} by {@code SecurityConfig}'s
 * {@code /organiser/**} path rule (FR-022).
 */
@Controller
@RequestMapping("/organiser/skills")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping
    public Mono<Rendering> list() {
        return Mono.just(Rendering.view("organiser/skills/list")
                .modelAttribute("skills", skillService.findAll())
                .build());
    }

    @GetMapping("/new")
    public Mono<Rendering> newForm() {
        return Mono.just(Rendering.view("organiser/skills/form").build());
    }

    @PostMapping
    public Mono<Rendering> create(ServerWebExchange exchange) {
        // WebFlux's @RequestParam only ever reads URL query parameters, never a form-urlencoded
        // request body (unlike Spring MVC) — so form fields are read via ServerWebExchange.getFormData().
        return exchange.getFormData().flatMap(form -> {
            String name = form.getFirst("name");
            return skillService
                    .create(name)
                    .<Rendering>map(skill -> Rendering.redirectTo("/organiser/skills")
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(
                            SkillConflictException.class,
                            ex -> Mono.just(Rendering.view("organiser/skills/form")
                                    .modelAttribute("error", ex.getMessage())
                                    .modelAttribute("name", name)
                                    .build()));
        });
    }

    @GetMapping("/{id}/edit")
    public Mono<Rendering> editForm(@PathVariable UUID id) {
        return skillService
                .findById(id)
                .map(skill -> Rendering.view("organiser/skills/form")
                        .modelAttribute("skillId", id)
                        .modelAttribute("name", skill.getName())
                        .build())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping("/{id}")
    public Mono<Rendering> update(@PathVariable UUID id, ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> {
            String name = form.getFirst("name");
            return skillService
                    .rename(id, name)
                    .<Rendering>map(skill -> Rendering.redirectTo("/organiser/skills")
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(
                            SkillConflictException.class,
                            ex -> Mono.just(Rendering.view("organiser/skills/form")
                                    .modelAttribute("error", ex.getMessage())
                                    .modelAttribute("skillId", id)
                                    .modelAttribute("name", name)
                                    .build()))
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
        });
    }

    @PostMapping("/{id}/delete")
    public Mono<Rendering> delete(@PathVariable UUID id) {
        return skillService
                .delete(id)
                .then(Mono.just(Rendering.redirectTo("/organiser/skills")
                        .status(HttpStatus.SEE_OTHER)
                        .build()))
                .onErrorResume(
                        SkillConflictException.class,
                        ex -> Mono.just(Rendering.view("organiser/skills/list")
                                .modelAttribute("skills", skillService.findAll())
                                .modelAttribute("error", ex.getMessage())
                                .status(HttpStatus.CONFLICT)
                                .build()));
    }
}

package net.fabcelhaft.hackathonorganiser.organiser.task;

import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.task.TaskService;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.reactive.result.view.Rendering;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The Organiser's Task list (contracts/organiser-ui.md; FR-020 - FR-030).
 *
 * <p>Access is restricted to {@code ROLE_ORGANISER} by {@code SecurityConfig}'s existing
 * {@code /organiser/**} path rule (FR-030) — this feature adds no security configuration. Follows
 * {@code EventDestinationController}'s {@code Rendering} + {@code ServerWebExchange.getFormData()}
 * pattern, and every handler returns {@code Mono<Rendering>} (Constitution Workflow §6).
 *
 * <p>Every action carries the current filter back in its redirect, so an Organiser working in the
 * done-inclusive view stays there instead of being dropped back to the default (FR-023).
 */
@Controller
@RequestMapping("/organiser/tasks")
public class TaskController {

    /** The single value of {@code ?show} that widens the list; anything else means undone-only. */
    private static final String SHOW_DONE = "done";

    private final TaskService taskService;
    private final UserRepository userRepository;

    public TaskController(TaskService taskService, UserRepository userRepository) {
        this.taskService = taskService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public Mono<Rendering> list(@RequestParam(name = "show", required = false) String show) {
        boolean includeDone = SHOW_DONE.equals(show);

        Mono<java.util.List<TaskService.TaskRow>> rows =
                includeDone ? taskService.findAllCapped() : taskService.findUndone();

        return rows.flatMap(fetched -> userRepository
                .findByOrganiserTrueOrderByDisplayNameAsc()
                .collectList()
                .map(organisers -> {
                    // FR-028a: one row beyond the cap was fetched purely to learn whether the list
                    // was cut off; it is never rendered (research.md §10).
                    boolean truncated = includeDone && fetched.size() > TaskService.DONE_VIEW_LIMIT;
                    var visible = truncated ? fetched.subList(0, TaskService.DONE_VIEW_LIMIT) : fetched;
                    return Rendering.view("organiser/tasks/list")
                            .modelAttribute("tasks", visible)
                            .modelAttribute("organisers", organisers)
                            .modelAttribute("includeDone", includeDone)
                            .modelAttribute("truncated", truncated)
                            .modelAttribute("doneViewLimit", TaskService.DONE_VIEW_LIMIT)
                            .build();
                }));
    }

    @PostMapping("/{id}/assign")
    public Mono<Rendering> assign(@PathVariable UUID id, ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> {
            UUID assignee = uuidValue(form.getFirst("assignee_user_id"));
            return taskService.assign(id, assignee).thenReturn(redirectToList(form.getFirst("show")));
        });
    }

    @PostMapping("/{id}/done")
    public Mono<Rendering> done(@PathVariable UUID id, ServerWebExchange exchange) {
        return exchange.getFormData()
                .flatMap(form -> taskService.markDone(id).thenReturn(redirectToList(form.getFirst("show"))));
    }

    @PostMapping("/{id}/reopen")
    public Mono<Rendering> reopen(@PathVariable UUID id, ServerWebExchange exchange) {
        return exchange.getFormData()
                .flatMap(form -> taskService.reopen(id).thenReturn(redirectToList(form.getFirst("show"))));
    }

    private static Rendering redirectToList(String show) {
        String target = SHOW_DONE.equals(show) ? "/organiser/tasks?show=done" : "/organiser/tasks";
        return Rendering.redirectTo(target).status(HttpStatus.SEE_OTHER).build();
    }

    /**
     * An empty value means "unassigned". An id that is not a current Organiser is treated the same
     * way rather than rejected, matching the stale-assignee edge case (FR-021).
     */
    private static UUID uuidValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

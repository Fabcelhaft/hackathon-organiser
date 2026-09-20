package net.fabcelhaft.hackathonorganiser.task;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.event.EventDestination;
import net.fabcelhaft.hackathonorganiser.event.EventType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Creates Tasks from fired Events and serves the Organiser's Task list (data-model.md "Task";
 * FR-002a, FR-013 - FR-029).
 *
 * <p>Tasks are machine-created only — there is no manual "create Task" action anywhere (spec.md
 * Assumptions), so {@link #createFromEvent} is the single entry point and takes an already-resolved
 * title rather than a pattern.
 */
@Service
public class TaskService {

    /**
     * FR-028a — a done-inclusive view lists only the most recently created done Tasks. One extra row
     * is fetched so the caller can tell whether the list was cut off without a second COUNT query
     * (research.md §10).
     */
    public static final int DONE_VIEW_LIMIT = 200;

    private static final String SELECT_COLUMNS =
            "SELECT t.id, t.event_destination_id, t.rule_name, t.event_type, t.title, "
                    + "t.assignee_user_id, t.done, t.done_at, t.created_at, "
                    // LEFT JOIN filtered on organiser = true: a Task assigned to someone who no
                    // longer holds the role renders as unassigned, so the row and the assignee
                    // dropdown can never disagree (research.md §9, spec.md Edge Cases).
                    + "u.display_name AS assignee_display_name "
                    + "FROM tasks t "
                    + "LEFT JOIN users u ON u.id = t.assignee_user_id AND u.organiser = true ";

    private final TaskRepository taskRepository;
    private final DatabaseClient databaseClient;

    public TaskService(TaskRepository taskRepository, DatabaseClient databaseClient) {
        this.taskRepository = taskRepository;
        this.databaseClient = databaseClient;
    }

    /** One Task-list row: the Task plus its assignee's display name, or null when unassigned. */
    public record TaskRow(Task task, String assigneeDisplayName) {}

    /**
     * Creates one Task for a fired Event (FR-016, FR-017).
     *
     * <p>{@code ruleName} is snapshotted here, not looked up later: {@code ON DELETE SET NULL} on
     * {@code event_destination_id} keeps a done Task alive past its Rule's deletion (FR-002a) and
     * would otherwise erase the association FR-017 requires it to record (research.md §7).
     *
     * <p>The Rule's default assignee is copied onto the Task (FR-005); a Rule without one produces an
     * unassigned Task.
     */
    public Mono<Task> createFromEvent(EventDestination rule, EventType eventType, String resolvedTitle) {
        Task task = new Task();
        task.setEventDestinationId(rule.getId());
        task.setRuleName(rule.getName());
        task.setEventType(eventType);
        task.setTitle(resolvedTitle);
        task.setAssigneeUserId(rule.getTaskDefaultAssigneeUserId());
        task.setDone(false);
        task.setCreatedAt(Instant.now());
        return taskRepository.save(task);
    }

    /**
     * Removes the undone Tasks a Rule created, keeping its done ones as a record of work actually
     * carried out (FR-002a). Called by {@code EventDestinationService.delete} before the Rule row
     * goes; the surviving done Tasks then have their {@code event_destination_id} nulled by the
     * database's {@code ON DELETE SET NULL}.
     */
    public Mono<Void> deleteUndoneForRule(UUID eventDestinationId) {
        return databaseClient
                .sql("DELETE FROM tasks WHERE event_destination_id = :did AND done = false")
                .bind("did", eventDestinationId)
                .then();
    }

    /** How many undone Tasks a Rule would take with it — the FR-002a delete confirmation's number. */
    public Mono<Long> countUndoneForRule(UUID eventDestinationId) {
        return databaseClient
                .sql("SELECT count(*) FROM tasks WHERE event_destination_id = :did AND done = false")
                .bind("did", eventDestinationId)
                .mapValue(Long.class)
                .one()
                .defaultIfEmpty(0L);
    }

    /**
     * Sets or clears a Task's assignee (FR-024).
     *
     * <p>Last write wins: there is deliberately no stale-write guard here, unlike {@code
     * EventDestinationService.update}'s {@code updatedAt} comparison. The spec decided a lost
     * assignee edit is preferable to an error dialog on a list two Organisers may both have open
     * (spec.md Edge Cases, research.md §12).
     */
    public Mono<Void> assign(UUID taskId, UUID assigneeUserId) {
        var spec = databaseClient
                .sql("UPDATE tasks SET assignee_user_id = :uid WHERE id = :id")
                .bind("id", taskId);
        spec = assigneeUserId == null ? spec.bindNull("uid", UUID.class) : spec.bind("uid", assigneeUserId);
        return spec.then();
    }

    /**
     * Marks a Task done (FR-025). Conditional on it being undone, so marking an already-done Task
     * done matches no row and completes normally rather than erroring (FR-027).
     */
    public Mono<Void> markDone(UUID taskId) {
        return databaseClient
                .sql("UPDATE tasks SET done = true, done_at = now() WHERE id = :id AND done = false")
                .bind("id", taskId)
                .then();
    }

    /** Reopens a done Task (FR-026). Conditional in the same way, for the same reason (FR-027). */
    public Mono<Void> reopen(UUID taskId) {
        return databaseClient
                .sql("UPDATE tasks SET done = false, done_at = NULL WHERE id = :id AND done = true")
                .bind("id", taskId)
                .then();
    }

    /** Every undone Task, newest first, with no cap — the default view (FR-022, FR-028, FR-028a). */
    public Mono<List<TaskRow>> findUndone() {
        return databaseClient
                .sql(SELECT_COLUMNS + "WHERE t.done = false ORDER BY t.created_at DESC")
                .map(TaskService::mapRow)
                .all()
                .collectList();
    }

    /**
     * Done and undone together, newest first, fetching one row beyond {@link #DONE_VIEW_LIMIT} so the
     * caller can tell the list was cut off (FR-023, FR-028a).
     */
    public Mono<List<TaskRow>> findAllCapped() {
        return databaseClient
                .sql(SELECT_COLUMNS + "ORDER BY t.created_at DESC LIMIT " + (DONE_VIEW_LIMIT + 1))
                .map(TaskService::mapRow)
                .all()
                .collectList();
    }

    private static TaskRow mapRow(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
        Task task = new Task();
        task.setId(row.get("id", UUID.class));
        task.setEventDestinationId(row.get("event_destination_id", UUID.class));
        task.setRuleName(row.get("rule_name", String.class));
        task.setEventType(EventType.valueOf(row.get("event_type", String.class)));
        task.setTitle(row.get("title", String.class));
        task.setAssigneeUserId(row.get("assignee_user_id", UUID.class));
        task.setDone(Boolean.TRUE.equals(row.get("done", Boolean.class)));
        task.setDoneAt(row.get("done_at", Instant.class));
        task.setCreatedAt(row.get("created_at", Instant.class));
        return new TaskRow(task, row.get("assignee_display_name", String.class));
    }
}

package net.fabcelhaft.hackathonorganiser.task;

import java.time.Instant;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.event.EventType;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A single unit of work a Task Rule produced when an Event occurred (spec.md Key Entities: Task;
 * data-model.md "Task" — FR-016, FR-017).
 *
 * <p>{@code id} is left {@code null} on construction: PostgreSQL assigns it via the {@code tasks.id}
 * column's {@code DEFAULT uuidv7()}, matching every other entity in this codebase — no
 * application-side ID generation exists anywhere here.
 *
 * <p>{@code title} is the <em>resolved</em> text, frozen at creation (FR-013). Editing the Rule's
 * pattern afterwards never rewrites it.
 *
 * <p>{@code ruleName} duplicates the producing Rule's name on purpose. {@code eventDestinationId} is
 * nullable with {@code ON DELETE SET NULL} so a done Task survives its Rule's deletion (FR-002a);
 * without this snapshot that deletion would erase exactly the association FR-017 requires the Task
 * to record. Mirrors {@code ParticipantService}'s audit {@code subject_label} precedent.
 */
@Table("tasks")
public class Task {

    @Id
    private UUID id;

    private UUID eventDestinationId;

    private String ruleName;

    private EventType eventType;

    private String title;

    private UUID assigneeUserId;

    private boolean done;

    private Instant doneAt;

    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getEventDestinationId() {
        return eventDestinationId;
    }

    public void setEventDestinationId(UUID eventDestinationId) {
        this.eventDestinationId = eventDestinationId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public UUID getAssigneeUserId() {
        return assigneeUserId;
    }

    public void setAssigneeUserId(UUID assigneeUserId) {
        this.assigneeUserId = assigneeUserId;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    public Instant getDoneAt() {
        return doneAt;
    }

    public void setDoneAt(Instant doneAt) {
        this.doneAt = doneAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

package net.fabcelhaft.hackathonorganiser.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.event.EventDestination;
import net.fabcelhaft.hackathonorganiser.event.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link TaskService} (T031).
 *
 * <p>Every chain is asserted with {@link StepVerifier}, which Constitution Development Workflow §4
 * requires for non-trivial reactive combinations.
 *
 * <p>The property under test for FR-027 is that {@code markDone}/{@code reopen} are <em>conditional</em>
 * updates: their SQL carries a {@code done = …} predicate, so re-applying one matches no row and the
 * chain completes normally instead of signalling. This is deliberately the opposite of feature 007's
 * {@code updatedAt} stale-write guard (research.md §12).
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private DatabaseClient.GenericExecuteSpec executeSpec;

    private TaskService service;

    @BeforeEach
    void setUp() {
        service = new TaskService(taskRepository, databaseClient);
        lenient().when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        lenient().when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        lenient().when(executeSpec.bindNull(anyString(), any())).thenReturn(executeSpec);
        lenient().when(executeSpec.then()).thenReturn(Mono.empty());
    }

    private String capturedSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(databaseClient).sql(sql.capture());
        return sql.getValue();
    }

    // ------------------------------------------------------------------ FR-025 / FR-027

    @Test
    void markDoneCompletesAndOnlyTouchesAnUndoneTask() {
        StepVerifier.create(service.markDone(UUID.randomUUID())).verifyComplete();

        String sql = capturedSql();
        assertThat(sql).contains("UPDATE tasks");
        assertThat(sql).contains("done = true");
        // The predicate is what makes a repeat a no-op rather than an error (FR-027).
        assertThat(sql).contains("done = false");
    }

    @Test
    void reopenCompletesAndOnlyTouchesADoneTask() {
        StepVerifier.create(service.reopen(UUID.randomUUID())).verifyComplete();

        String sql = capturedSql();
        assertThat(sql).contains("UPDATE tasks");
        assertThat(sql).contains("done = false");
        assertThat(sql).contains("done = true");
        assertThat(sql).contains("done_at = NULL");
    }

    /** FR-027: nothing in either chain can signal an error when no row matches. */
    @Test
    void repeatingMarkDoneAndReopenNeverSignalsAnError() {
        UUID id = UUID.randomUUID();

        StepVerifier.create(service.markDone(id).then(service.markDone(id))).verifyComplete();
        StepVerifier.create(service.reopen(id).then(service.reopen(id))).verifyComplete();
    }

    // ------------------------------------------------------------------ FR-024

    /** Last write wins — no stale-write guard, by design (spec.md Edge Cases). */
    @Test
    void assignCompletesAndTouchesOnlyThatTask() {
        StepVerifier.create(service.assign(UUID.randomUUID(), UUID.randomUUID()))
                .verifyComplete();

        String sql = capturedSql();
        assertThat(sql).contains("UPDATE tasks");
        assertThat(sql).contains("assignee_user_id");
        assertThat(sql).contains("WHERE id = :id");
        assertThat(sql).doesNotContain("updated_at");
    }

    @Test
    void assignWithNoUserClearsTheAssignee() {
        StepVerifier.create(service.assign(UUID.randomUUID(), null)).verifyComplete();

        verify(executeSpec).bindNull(eq("uid"), any());
    }

    // ------------------------------------------------------------------ FR-002a

    @Test
    void deleteUndoneForRuleTargetsOnlyUndoneTasksOfThatRule() {
        StepVerifier.create(service.deleteUndoneForRule(UUID.randomUUID())).verifyComplete();

        String sql = capturedSql();
        assertThat(sql).contains("DELETE FROM tasks");
        assertThat(sql).contains("event_destination_id = :did");
        assertThat(sql).contains("done = false");
    }

    // ------------------------------------------------------------------ FR-005 / FR-017

    /**
     * FR-005 — the half /speckit-analyze found uncovered. The Rule's default assignee must land on
     * the Task, and {@code ruleName} must be snapshotted so a done Task stays attributable after its
     * Rule is deleted (FR-017, research.md §7).
     */
    @Test
    void createFromEventCopiesTheDefaultAssigneeAndSnapshotsTheRuleName() {
        UUID assignee = UUID.randomUUID();
        EventDestination rule = new EventDestination();
        rule.setId(UUID.randomUUID());
        rule.setName("Topic review");
        rule.setTaskDefaultAssigneeUserId(assignee);
        lenient()
                .when(taskRepository.save(any(Task.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.createFromEvent(rule, EventType.TOPIC_PROPOSED, "Review: Robot Arm"))
                .assertNext(task -> {
                    assertThat(task.getAssigneeUserId()).isEqualTo(assignee);
                    assertThat(task.getRuleName()).isEqualTo("Topic review");
                    assertThat(task.getEventDestinationId()).isEqualTo(rule.getId());
                    assertThat(task.getEventType()).isEqualTo(EventType.TOPIC_PROPOSED);
                    assertThat(task.getTitle()).isEqualTo("Review: Robot Arm");
                    assertThat(task.isDone()).isFalse();
                    assertThat(task.getDoneAt()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void createFromEventLeavesTheTaskUnassignedWhenTheRuleHasNoDefault() {
        EventDestination rule = new EventDestination();
        rule.setId(UUID.randomUUID());
        rule.setName("Topic review");
        lenient()
                .when(taskRepository.save(any(Task.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.createFromEvent(rule, EventType.TOPIC_PROPOSED, "Review: Robot Arm"))
                .assertNext(task -> assertThat(task.getAssigneeUserId()).isNull())
                .verifyComplete();
    }
}

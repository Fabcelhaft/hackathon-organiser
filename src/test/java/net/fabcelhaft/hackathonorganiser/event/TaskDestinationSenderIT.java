package net.fabcelhaft.hackathonorganiser.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.task.Task;
import net.fabcelhaft.hackathonorganiser.task.TaskService;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Integration tests for {@link TaskDestinationSender} (T012, T013) — an Event fired at an enabled
 * Task Rule produces a Task with the resolved title (FR-016), a disabled Rule produces none
 * (FR-019), repeats are never de-duplicated (FR-016), the Rule's default assignee is applied
 * (FR-005), and a failure never reaches the caller (FR-018, SC-003).
 */
@SpringBootTest
@Testcontainers
class TaskDestinationSenderIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @Autowired
    TaskDestinationSender taskDestinationSender;

    @Autowired
    EventPublisher eventPublisher;

    @Autowired
    EventDestinationService eventDestinationService;

    @Autowired
    TaskService taskService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    DatabaseClient databaseClient;

    private static final String TOPIC_PROPOSED_JSON =
            "{\"eventType\":\"TOPIC_PROPOSED\",\"topic\":{\"name\":\"Robot Arm\",\"description\":null}}";

    // ------------------------------------------------------------------ helpers

    private EventDestination taskRule(String pattern, UUID defaultAssignee) {
        EventDestination rule = new EventDestination();
        rule.setId(UUID.randomUUID());
        rule.setName("Rule " + UUID.randomUUID());
        rule.setType(EventDestinationType.TASK);
        rule.setEnabled(true);
        rule.setTaskTitlePattern(pattern);
        rule.setTaskDefaultAssigneeUserId(defaultAssignee);
        return rule;
    }

    /** A persisted Rule, so a Task's event_destination_id FK resolves. */
    private EventDestination persistedTaskRule(String pattern, UUID defaultAssignee, List<EventType> eventTypes) {
        EventDestination created = eventDestinationService
                .create(
                        "Rule " + UUID.randomUUID(),
                        EventDestinationType.TASK,
                        null,
                        null,
                        null,
                        null,
                        pattern,
                        defaultAssignee,
                        eventTypes)
                .block(Duration.ofSeconds(10));
        return created;
    }

    private List<Task> tasksForRule(UUID ruleId) {
        return databaseClient
                .sql("SELECT id, event_destination_id, rule_name, event_type, title, assignee_user_id, "
                        + "done, done_at, created_at FROM tasks WHERE event_destination_id = :did")
                .bind("did", ruleId)
                .map((row, meta) -> {
                    Task task = new Task();
                    task.setId(row.get("id", UUID.class));
                    task.setRuleName(row.get("rule_name", String.class));
                    task.setTitle(row.get("title", String.class));
                    task.setAssigneeUserId(row.get("assignee_user_id", UUID.class));
                    task.setDone(Boolean.TRUE.equals(row.get("done", Boolean.class)));
                    return task;
                })
                .all()
                .collectList()
                .block(Duration.ofSeconds(10));
    }

    private UUID organiserId() {
        User user = new User();
        user.setOidcSubject("sub-" + UUID.randomUUID());
        user.setDisplayName("Organiser " + UUID.randomUUID());
        user.setOrganiser(true);
        return userRepository.save(user).block(Duration.ofSeconds(10)).getId();
    }

    // ------------------------------------------------------------------ T012

    @Test
    void anEnabledTaskRuleProducesATaskWithTheResolvedTitle() {
        EventDestination rule = persistedTaskRule("Review new topic: {{topic.name}}", null, List.of());

        StepVerifier.create(taskDestinationSender.send(rule, TOPIC_PROPOSED_JSON))
                .verifyComplete();

        List<Task> tasks = tasksForRule(rule.getId());
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getTitle()).isEqualTo("Review new topic: Robot Arm");
        assertThat(tasks.get(0).isDone()).isFalse();
        assertThat(tasks.get(0).getRuleName()).isEqualTo(rule.getName());
    }

    /** FR-016: every occurrence produces its own Task; identical titles are never merged. */
    @Test
    void twoIdenticalEventsProduceTwoSeparateTasks() {
        EventDestination rule = persistedTaskRule("Review new topic: {{topic.name}}", null, List.of());

        taskDestinationSender.send(rule, TOPIC_PROPOSED_JSON).block(Duration.ofSeconds(10));
        taskDestinationSender.send(rule, TOPIC_PROPOSED_JSON).block(Duration.ofSeconds(10));

        List<Task> tasks = tasksForRule(rule.getId());
        assertThat(tasks).hasSize(2);
        assertThat(tasks).allSatisfy(t -> assertThat(t.getTitle()).isEqualTo("Review new topic: Robot Arm"));
    }

    /** FR-005 — the half /speckit-analyze found uncovered: the default assignee must reach the Task. */
    @Test
    void aRuleWithADefaultAssigneeProducesAnAssignedTask() {
        UUID assignee = organiserId();
        EventDestination rule = persistedTaskRule("Review {{topic.name}}", assignee, List.of());

        taskDestinationSender.send(rule, TOPIC_PROPOSED_JSON).block(Duration.ofSeconds(10));

        assertThat(tasksForRule(rule.getId())).singleElement().satisfies(task -> assertThat(task.getAssigneeUserId())
                .isEqualTo(assignee));
    }

    @Test
    void aRuleWithoutADefaultAssigneeProducesAnUnassignedTask() {
        EventDestination rule = persistedTaskRule("Review {{topic.name}}", null, List.of());

        taskDestinationSender.send(rule, TOPIC_PROPOSED_JSON).block(Duration.ofSeconds(10));

        assertThat(tasksForRule(rule.getId())).singleElement().satisfies(task -> assertThat(task.getAssigneeUserId())
                .isNull());
    }

    /** research.md §3 end-to-end: a JSON null must not write the word "null" into a stored title. */
    @Test
    void aJsonNullNeverReachesTheStoredTitle() {
        EventDestination rule = persistedTaskRule("Topic: {{topic.name}} ({{topic.description}})", null, List.of());

        taskDestinationSender.send(rule, TOPIC_PROPOSED_JSON).block(Duration.ofSeconds(10));

        assertThat(tasksForRule(rule.getId())).singleElement().satisfies(task -> {
            assertThat(task.getTitle()).isEqualTo("Topic: Robot Arm ()");
            assertThat(task.getTitle()).doesNotContain("null");
        });
    }

    /** FR-019, end to end through EventPublisher: a disabled Rule is never even looked up. */
    @Test
    void aDisabledRuleProducesNoTask() throws Exception {
        EventDestination rule = persistedTaskRule("Review {{topic.name}}", null, List.of(EventType.TOPIC_PROPOSED));
        // create() always starts disabled (FR-006), so no enable() call here.

        eventPublisher.publish(new DomainEvent(EventType.TOPIC_PROPOSED, Map.of("topic", Map.of("name", "Robot Arm"))));
        Thread.sleep(1500); // nothing to await on; a Task must NOT appear

        assertThat(tasksForRule(rule.getId())).isEmpty();
    }

    /** The EventPublisher wiring itself (FR-016): an enabled, subscribed Rule fires. */
    @Test
    void anEnabledSubscribedRuleFiresThroughEventPublisher() {
        EventDestination rule = persistedTaskRule("Review: {{topic.name}}", null, List.of(EventType.TOPIC_PROPOSED));
        eventDestinationService.enable(rule.getId()).block(Duration.ofSeconds(10));

        eventPublisher.publish(new DomainEvent(EventType.TOPIC_PROPOSED, Map.of("topic", Map.of("name", "Robot Arm"))));

        List<Task> tasks = awaitTasks(rule.getId());
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getTitle()).isEqualTo("Review: Robot Arm");
    }

    /** Dispatch is detached by design, so poll rather than await a latch (research.md §6). */
    private List<Task> awaitTasks(UUID ruleId) {
        for (int attempt = 0; attempt < 50; attempt++) {
            List<Task> tasks = tasksForRule(ruleId);
            if (!tasks.isEmpty()) {
                return tasks;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return tasksForRule(ruleId);
    }

    // ------------------------------------------------------------------ T013

    /**
     * FR-018 / SC-003, verified with {@code StepVerifier} as Constitution Development Workflow §4
     * requires. The Rule's id references no row, so the insert violates the FK — the returned Mono
     * must still complete empty rather than signalling, because {@link EventPublisher} subscribes to
     * it detached and a signalled error would surface nowhere useful.
     */
    @Test
    void aFailingTaskWriteCompletesEmptyInsteadOfSignallingAnError() {
        EventDestination unpersisted = taskRule("Review {{topic.name}}", null);

        StepVerifier.create(taskDestinationSender.send(unpersisted, TOPIC_PROPOSED_JSON))
                .verifyComplete();
    }

    /** Malformed JSON is a swallowed, logged failure — never an error the caller sees. */
    @Test
    void malformedEventJsonCompletesEmpty() {
        EventDestination rule = persistedTaskRule("Review {{topic.name}}", null, List.of());

        StepVerifier.create(taskDestinationSender.send(rule, "{not json"))
                .verifyComplete();

        assertThat(tasksForRule(rule.getId())).isEmpty();
    }

    /** An unrecognised eventType is skipped without creating a nameless Task. */
    @Test
    void anUnrecognisedEventTypeCreatesNoTask() {
        EventDestination rule = persistedTaskRule("Review {{topic.name}}", null, List.of());

        StepVerifier.create(taskDestinationSender.send(rule, "{\"eventType\":\"NOT_A_REAL_TYPE\"}"))
                .verifyComplete();

        assertThat(tasksForRule(rule.getId())).isEmpty();
    }

    /** The triggering caller's own chain is never coupled to delivery (FR-018). */
    @Test
    void publishReturnsImmediatelyEvenWhenTaskCreationWillFail() {
        StepVerifier.create(Mono.fromRunnable(() -> eventPublisher.publish(
                        new DomainEvent(EventType.TOPIC_PROPOSED, Map.of("topic", Map.of("name", "Robot Arm"))))))
                .verifyComplete();
    }
}

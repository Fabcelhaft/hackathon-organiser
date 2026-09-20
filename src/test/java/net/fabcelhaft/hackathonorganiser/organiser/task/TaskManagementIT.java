package net.fabcelhaft.hackathonorganiser.organiser.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.event.EventType;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.task.TaskService;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.OidcLoginMutator;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration tests for {@link TaskController} (T032, T033, T040, T041, T042) — assign/Save/Done/
 * Reopen (FR-024 - FR-027), the default undone-only filter and its round-trip (FR-022, FR-023), the
 * done-view cap (FR-028a), and identical titles staying distinguishable (FR-021a).
 *
 * <p>Follows {@code EventDestinationManagementIT}'s {@code WebTestClient} + {@code mockOidcLogin()}
 * + Testcontainers pattern, with a real {@code HackathonOidcUser} principal.
 */
@SpringBootTest
@Testcontainers
class TaskManagementIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    UserRepository userRepository;

    @Autowired
    DatabaseClient databaseClient;

    @Autowired
    TaskService taskService;

    WebTestClient webTestClient;

    @BeforeEach
    void setUpWebTestClient() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient()
                .responseTimeout(Duration.ofSeconds(30))
                // The done-inclusive view renders up to 200 rows, which comfortably exceeds
                // WebTestClient's 256KB default buffer.
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }

    /** Tasks are class-scoped state in a shared container; each test starts from an empty table. */
    @BeforeEach
    void clearTasks() {
        databaseClient.sql("DELETE FROM tasks").then().block(Duration.ofSeconds(10));
    }

    // ------------------------------------------------------------------ helpers

    /** Tasks are machine-created, so tests insert directly rather than firing Events. */
    private UUID insertTask(String title, boolean done, UUID assignee, Instant createdAt) {
        var spec = databaseClient
                .sql("INSERT INTO tasks (rule_name, event_type, title, assignee_user_id, done, done_at, created_at) "
                        + "VALUES (:rn, :et, :title, :uid, :done, :doneAt, :createdAt) RETURNING id")
                .bind("rn", "Test Rule")
                .bind("et", EventType.TOPIC_PROPOSED.name())
                .bind("title", title)
                .bind("done", done)
                .bind("createdAt", createdAt);
        spec = assignee == null ? spec.bindNull("uid", UUID.class) : spec.bind("uid", assignee);
        spec = done ? spec.bind("doneAt", Instant.now()) : spec.bindNull("doneAt", Instant.class);
        return spec.mapValue(UUID.class).one().block(Duration.ofSeconds(10));
    }

    private UUID insertTask(String title, boolean done) {
        return insertTask(title, done, null, Instant.now());
    }

    private String taskState(UUID id, String column) {
        // Reactor's map() forbids null, so a NULL column is wrapped rather than returned bare.
        java.util.Optional<Object> value = databaseClient
                .sql("SELECT " + column + " AS v FROM tasks WHERE id = :id")
                .bind("id", id)
                .map((row, meta) -> java.util.Optional.ofNullable(row.get("v")))
                .one()
                .block(Duration.ofSeconds(10));
        return value == null || value.isEmpty() ? null : value.get().toString();
    }

    private User persistUser(String displayName, boolean organiser) {
        User user = new User();
        user.setOidcSubject("sub-" + UUID.randomUUID());
        user.setDisplayName(displayName);
        user.setEmail(displayName.toLowerCase().replace(' ', '.') + "@example.com");
        user.setOrganiser(organiser);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).block(Duration.ofSeconds(10));
    }

    private OidcLoginMutator organiser() {
        return loginAsUser(persistUser("Organiser " + UUID.randomUUID(), true));
    }

    private static OidcLoginMutator loginAsUser(User user) {
        Instant issuedAt = Instant.now();
        OidcIdToken idToken = OidcIdToken.withTokenValue("token-value")
                .subject(user.getOidcSubject())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(300))
                .claim("name", user.getDisplayName())
                .build();
        List<GrantedAuthority> authorities = user.isOrganiser()
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ORGANISER"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));
        DefaultOidcUser delegate = new DefaultOidcUser(authorities, idToken);
        return mockOidcLogin().oidcUser(new HackathonOidcUser(user, delegate));
    }

    // ================================================================== T032 (FR-024 - FR-027)

    @Test
    void assigningATaskPersistsAcrossAReload() {
        UUID taskId = insertTask("Assign me " + UUID.randomUUID(), false);
        User assignee = persistUser("Assignee " + UUID.randomUUID(), true);

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/tasks/{id}/assign", taskId)
                .body(BodyInserters.fromFormData("assignee_user_id", assignee.getId().toString()))
                .exchange()
                .expectStatus()
                .isSeeOther();

        assertThat(taskState(taskId, "assignee_user_id")).isEqualTo(assignee.getId().toString());

        webTestClient
                .mutateWith(organiser())
                .get()
                .uri("/organiser/tasks")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains(assignee.getDisplayName()));
    }

    @Test
    void clearingTheAssigneeUnassignsTheTask() {
        User assignee = persistUser("Assignee " + UUID.randomUUID(), true);
        UUID taskId = insertTask("Unassign me " + UUID.randomUUID(), false, assignee.getId(), Instant.now());

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/tasks/{id}/assign", taskId)
                .body(BodyInserters.fromFormData("assignee_user_id", ""))
                .exchange()
                .expectStatus()
                .isSeeOther();

        assertThat(taskState(taskId, "assignee_user_id")).isNull();
    }

    @Test
    void doneThenReopenRoundTrips() {
        UUID taskId = insertTask("Round trip " + UUID.randomUUID(), false);

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/tasks/{id}/done", taskId)
                .body(BodyInserters.fromFormData("show", ""))
                .exchange()
                .expectStatus()
                .isSeeOther();

        assertThat(taskState(taskId, "done")).isEqualTo("true");
        assertThat(taskState(taskId, "done_at")).isNotNull();

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/tasks/{id}/reopen", taskId)
                .body(BodyInserters.fromFormData("show", "done"))
                .exchange()
                .expectStatus()
                .isSeeOther();

        assertThat(taskState(taskId, "done")).isEqualTo("false");
        assertThat(taskState(taskId, "done_at")).isNull();
    }

    /** FR-027: a second Done is accepted without error and leaves the Task done. */
    @Test
    void markingAnAlreadyDoneTaskDoneIsAcceptedWithoutError() {
        UUID taskId = insertTask("Double done " + UUID.randomUUID(), false);

        for (int i = 0; i < 2; i++) {
            webTestClient
                    .mutateWith(organiser())
                    .post()
                    .uri("/organiser/tasks/{id}/done", taskId)
                    .body(BodyInserters.fromFormData("show", ""))
                    .exchange()
                    .expectStatus()
                    .isSeeOther();
        }

        assertThat(taskState(taskId, "done")).isEqualTo("true");
    }

    @Test
    void aDoneTaskLeavesTheDefaultView() {
        String title = "Vanishing " + UUID.randomUUID();
        UUID taskId = insertTask(title, false);

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/tasks/{id}/done", taskId)
                .body(BodyInserters.fromFormData("show", ""))
                .exchange()
                .expectStatus()
                .isSeeOther();

        webTestClient
                .mutateWith(organiser())
                .get()
                .uri("/organiser/tasks")
                .exchange()
                .expectBody(String.class)
                .value(body -> assertThat(body).doesNotContain(title));
    }

    // ================================================================== T033 (stale assignee)

    /** spec.md Edge Cases: losing the Organiser role leaves the Task listed, as unassigned. */
    @Test
    void aTaskAssignedToSomeoneNoLongerAnOrganiserRendersAsUnassigned() {
        User formerOrganiser = persistUser("Former " + UUID.randomUUID(), true);
        String title = "Orphaned assignee " + UUID.randomUUID();
        UUID taskId = insertTask(title, false, formerOrganiser.getId(), Instant.now());

        formerOrganiser.setOrganiser(false);
        userRepository.save(formerOrganiser).block(Duration.ofSeconds(10));

        webTestClient
                .mutateWith(organiser())
                .get()
                .uri("/organiser/tasks")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains(title);
                    assertThat(body).doesNotContain(formerOrganiser.getDisplayName());
                });

        // The row itself is untouched — nothing is rewritten on a role change.
        assertThat(taskState(taskId, "assignee_user_id"))
                .isEqualTo(formerOrganiser.getId().toString());
    }

    // ================================================================== T040 (FR-022, FR-023)

    @Test
    void theDefaultViewListsOnlyUndoneTasksAndShowDoneIncludesThem() {
        String undone = "Outstanding " + UUID.randomUUID();
        String done = "Completed " + UUID.randomUUID();
        insertTask(undone, false);
        insertTask(done, true);

        webTestClient
                .mutateWith(organiser())
                .get()
                .uri("/organiser/tasks")
                .exchange()
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains(undone);
                    assertThat(body).doesNotContain(done);
                });

        webTestClient
                .mutateWith(organiser())
                .get()
                .uri("/organiser/tasks?show=done")
                .exchange()
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains(undone);
                    assertThat(body).contains(done);
                    assertThat(body).contains("completed "); // FR-021a completion time
                });
    }

    // ================================================================== T041 (FR-023 round-trip)

    @Test
    void anActionTakenFromTheDoneInclusiveViewReturnsToThatView() {
        UUID taskId = insertTask("From done view " + UUID.randomUUID(), true);

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/tasks/{id}/reopen", taskId)
                .body(BodyInserters.fromFormData("show", "done"))
                .exchange()
                .expectStatus()
                .isSeeOther()
                .expectHeader()
                .valueEquals("Location", "/organiser/tasks?show=done");
    }

    @Test
    void anActionTakenFromTheDefaultViewReturnsToTheDefaultView() {
        UUID taskId = insertTask("From default view " + UUID.randomUUID(), false);

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/tasks/{id}/done", taskId)
                .body(BodyInserters.fromFormData("show", ""))
                .exchange()
                .expectStatus()
                .isSeeOther()
                .expectHeader()
                .valueEquals("Location", "/organiser/tasks");
    }

    // ================================================================== T042 (FR-028a, FR-021a)

    /** FR-028a: the done-inclusive view is capped and says so; undone Tasks are never withheld. */
    @Test
    void theDoneInclusiveViewIsCappedAndSaysSo() {
        Instant base = Instant.now().minusSeconds(100_000);
        for (int i = 0; i < TaskService.DONE_VIEW_LIMIT + 5; i++) {
            insertTask("Bulk done " + i + " " + UUID.randomUUID(), true, null, base.plusSeconds(i));
        }

        webTestClient
                .mutateWith(organiser())
                .get()
                .uri("/organiser/tasks?show=done")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("most recently created Tasks"));

        assertThat(taskService.findAllCapped().block(Duration.ofSeconds(20)))
                .hasSize(TaskService.DONE_VIEW_LIMIT + 1);
    }

    /**
     * FR-021a: two Tasks with identical titles are told apart by their secondary text.
     *
     * <p>Asserts the two rendered timestamps <em>differ</em> rather than matching exact strings: a
     * Java {@code Instant} carries nanoseconds while Postgres' {@code timestamptz} stores — and
     * rounds to — microseconds, so the round-tripped value is not textually identical to the one
     * written. {@code EventDestinationService.roundToMicros} documents the same behaviour. Being
     * distinguishable is the requirement; exact round-tripping is not.
     */
    @Test
    void twoIdenticallyTitledTasksAreDistinguishableByTheirCreatedTimes() {
        String title = "Review new topic: Robot Arm " + UUID.randomUUID();
        Instant earlier = Instant.now().minusSeconds(3600);
        Instant later = Instant.now();
        insertTask(title, false, null, earlier);
        insertTask(title, false, null, later);

        webTestClient
                .mutateWith(organiser())
                .get()
                .uri("/organiser/tasks")
                .exchange()
                .expectBody(String.class)
                .value(body -> {
                    // Both rows carry the same title, so only the secondary text separates them.
                    assertThat(body).contains(title);
                    var rendered = java.util.regex.Pattern.compile("created ([^<\\s]+)")
                            .matcher(body)
                            .results()
                            .map(match -> match.group(1))
                            .toList();
                    assertThat(rendered).hasSize(2);
                    assertThat(rendered.get(0)).isNotEqualTo(rendered.get(1));
                    // Newest first (FR-028).
                    assertThat(Instant.parse(rendered.get(0))).isAfter(Instant.parse(rendered.get(1)));
                });
    }

    // ================================================================== T049 (SC-006)

    /**
     * SC-006: the default undone view stays responsive at 500 outstanding Tasks, and Save/Done still
     * work from it. The assertion is on completion within a generous bound, not a micro-benchmark —
     * the point is that the uncapped undone view does not degrade into something unusable, which is
     * what {@code tasks_done_created_idx} on {@code (done, created_at DESC)} exists for.
     */
    @Test
    void theDefaultViewStaysUsableWithFiveHundredOutstandingTasks() {
        Instant base = Instant.now().minusSeconds(500_000);
        UUID firstId = null;
        for (int i = 0; i < 500; i++) {
            UUID id = insertTask("Bulk outstanding " + i, false, null, base.plusSeconds(i));
            if (firstId == null) {
                firstId = id;
            }
        }

        long startedAt = System.nanoTime();
        webTestClient
                .mutateWith(organiser())
                .get()
                .uri("/organiser/tasks")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("Bulk outstanding 499"));
        Duration listLoad = Duration.ofNanos(System.nanoTime() - startedAt);

        // Actions from a 500-row list behave exactly as from a short one (FR-024, FR-025).
        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/tasks/{id}/done", firstId)
                .body(BodyInserters.fromFormData("show", ""))
                .exchange()
                .expectStatus()
                .isSeeOther();

        assertThat(taskState(firstId, "done")).isEqualTo("true");
        assertThat(listLoad)
                .as("500-row undone view load time")
                .isLessThan(Duration.ofSeconds(10));
    }

    /** FR-029: an explanatory empty state, not a bare empty table. */
    @Test
    void anEmptyDefaultViewShowsAnExplanatoryMessage() {
        webTestClient
                .mutateWith(organiser())
                .get()
                .uri("/organiser/tasks")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("No outstanding Tasks"));
    }
}

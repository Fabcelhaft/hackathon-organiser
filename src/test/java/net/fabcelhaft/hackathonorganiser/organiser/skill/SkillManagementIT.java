package net.fabcelhaft.hackathonorganiser.organiser.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantRepository;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
import net.fabcelhaft.hackathonorganiser.skill.Skill;
import net.fabcelhaft.hackathonorganiser.skill.SkillRepository;
import net.fabcelhaft.hackathonorganiser.topic.Topic;
import net.fabcelhaft.hackathonorganiser.topic.TopicRepository;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.OidcLoginMutator;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration tests for User Story 2's Skill catalog (T018), covering
 * specs/002-core-domain-model/contracts/catalog-management.md end to end against the real
 * {@code SecurityWebFilterChain} and {@code SkillRepository} — no hand-mocked security
 * substitute (research.md §6), following the same {@code WebTestClient} + {@code mockOidcLogin()}
 * + Testcontainers pattern as {@code organiser.user.UserManagementIT}.
 *
 * <p>{@code participant_skills} now exists as of User Story 3 (T038): the "not referenced ->
 * delete succeeds" path continues to be exercised here for real against the real database
 * (originally proving {@code SkillService}'s defensive "relation does not exist" handling before
 * this table existed; now simply proving the ordinary happy path still holds now that the table
 * is real). The "referenced -> blocked" path — previously only unit-tested with mocks in
 * {@code SkillServiceTest} because no real referencing table existed yet — is additionally proven
 * here end-to-end against a real Postgres row in {@code participant_skills}
 * ({@link #organiserCannotDeleteASkillStillAssignedToAParticipant()}). {@code topic_skills} now
 * exists as of User Story 4 (T046) too, so its half of the guard is proven the same way here
 * against a real Postgres row ({@link #organiserCannotDeleteASkillStillAssociatedWithATopic()}).
 */
@SpringBootTest
@Testcontainers
class SkillManagementIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @Autowired
    ApplicationContext applicationContext;

    WebTestClient webTestClient;

    @BeforeEach
    void setUpWebTestClient() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    @Autowired
    SkillRepository skillRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ParticipantRepository participantRepository;

    @Autowired
    TopicRepository topicRepository;

    @Autowired
    DatabaseClient databaseClient;

    // --- Listing --------------------------------------------------------------------------------

    @Test
    void organiserCanListAndViewSkillForms() {
        persistSkill("List Target " + UUID.randomUUID());

        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/skills")
                .exchange()
                .expectStatus().isOk();

        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/skills/new")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void editingAnUnknownSkillReturnsNotFound() {
        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/skills/{id}/edit", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- Create ---------------------------------------------------------------------------------

    @Test
    void organiserCanCreateASkill() {
        String name = "Rust " + UUID.randomUUID();

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/skills")
                .body(BodyInserters.fromFormData("name", name))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER)
                .expectHeader().valueEquals("Location", "/organiser/skills");

        assertThat(skillRepository.findAll().collectList().block())
                .extracting(Skill::getName)
                .contains(name);
    }

    @Test
    void createRejectsCaseInsensitiveDuplicateName() {
        String name = "Kotlin " + UUID.randomUUID();
        persistSkill(name);

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/skills")
                .body(BodyInserters.fromFormData("name", name.toUpperCase()))
                .exchange()
                .expectStatus().isOk(); // form re-rendered with error, not redirected
    }

    // --- Rename ---------------------------------------------------------------------------------

    @Test
    void organiserCanRenameASkill() {
        Skill skill = persistSkill("Old Name " + UUID.randomUUID());
        String newName = "New Name " + UUID.randomUUID();

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/skills/{id}", skill.getId())
                .body(BodyInserters.fromFormData("name", newName))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(skillRepository.findById(skill.getId()).block().getName()).isEqualTo(newName);
    }

    @Test
    void renameRejectsCaseInsensitiveDuplicateAgainstAnotherSkill() {
        String otherName = "Existing " + UUID.randomUUID();
        persistSkill(otherName);
        Skill toRename = persistSkill("Renamable " + UUID.randomUUID());

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/skills/{id}", toRename.getId())
                .body(BodyInserters.fromFormData("name", otherName.toUpperCase()))
                .exchange()
                .expectStatus().isOk(); // form re-rendered with error, not redirected

        assertThat(skillRepository.findById(toRename.getId()).block().getName())
                .isEqualTo(toRename.getName());
    }

    @Test
    void renamingAnUnknownSkillReturnsNotFound() {
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/skills/{id}", UUID.randomUUID())
                .body(BodyInserters.fromFormData("name", "Whatever"))
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- Delete: not-referenced path succeeds (FR-023) -----------------------------------------

    @Test
    void organiserCanDeleteASkillThatIsNotReferenced() {
        Skill skill = persistSkill("Deletable " + UUID.randomUUID());

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/skills/{id}/delete", skill.getId())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(skillRepository.findById(skill.getId()).block()).isNull();
    }

    // --- Delete: referenced path is genuinely blocked now that participant_skills exists (FR-023)

    @Test
    void organiserCannotDeleteASkillStillAssignedToAParticipant() {
        Skill skill = persistSkill("Assigned " + UUID.randomUUID());
        User user = persistUser("sub-" + UUID.randomUUID());
        Participant participant = persistParticipant(user.getId());
        databaseClient
                .sql("INSERT INTO participant_skills (participant_id, skill_id) VALUES (:pid, :sid)")
                .bind("pid", participant.getId())
                .bind("sid", skill.getId())
                .then()
                .block();

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/skills/{id}/delete", skill.getId())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);

        assertThat(skillRepository.findById(skill.getId()).block()).isNotNull();
    }

    // --- Delete: referenced path is genuinely blocked now that topic_skills exists (FR-023) -----

    @Test
    void organiserCannotDeleteASkillStillAssociatedWithATopic() {
        Skill skill = persistSkill("Topic Assigned " + UUID.randomUUID());
        User creator = persistUser("sub-" + UUID.randomUUID());
        Topic topic = persistTopic(creator.getId(), "Guard Topic " + UUID.randomUUID(), "Desc");
        databaseClient
                .sql("INSERT INTO topic_skills (topic_id, skill_id) VALUES (:tid, :sid)")
                .bind("tid", topic.getId())
                .bind("sid", skill.getId())
                .then()
                .block();

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/skills/{id}/delete", skill.getId())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);

        assertThat(skillRepository.findById(skill.getId()).block()).isNotNull();
    }

    // --- Non-Organiser denied on every route (FR-022, SC-004) -----------------------------------

    @Test
    void nonOrganiserIsDeniedOnEveryRoute() {
        Skill skill = persistSkill("Guarded " + UUID.randomUUID());

        webTestClient.mutateWith(standardUser())
                .get().uri("/organiser/skills")
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .get().uri("/organiser/skills/new")
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/skills")
                .body(BodyInserters.fromFormData("name", "Nope " + UUID.randomUUID()))
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .get().uri("/organiser/skills/{id}/edit", skill.getId())
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/skills/{id}", skill.getId())
                .body(BodyInserters.fromFormData("name", "Nope"))
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/skills/{id}/delete", skill.getId())
                .exchange()
                .expectStatus().isForbidden();
    }

    // --- Test helpers ----------------------------------------------------------------------------

    private Skill persistSkill(String name) {
        Skill skill = new Skill();
        skill.setName(name);
        skill.setCreatedAt(Instant.now());
        skill.setUpdatedAt(Instant.now());
        return skillRepository.save(skill).block();
    }

    private User persistUser(String oidcSubject) {
        User user = new User();
        user.setOidcSubject(oidcSubject);
        user.setDisplayName("Skill Guard User " + oidcSubject);
        user.setOrganiser(false);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).block();
    }

    private Participant persistParticipant(UUID userId) {
        Participant participant = new Participant();
        participant.setUserId(userId);
        participant.setStatus(ParticipantStatus.ACTIVE);
        participant.setCreatedAt(Instant.now());
        participant.setUpdatedAt(Instant.now());
        return participantRepository.save(participant).block();
    }

    private Topic persistTopic(UUID creatorId, String name, String description) {
        Topic topic = new Topic();
        topic.setName(name);
        topic.setDescription(description);
        topic.setCreatedByUserId(creatorId);
        topic.setCreatedAt(Instant.now());
        topic.setUpdatedAt(Instant.now());
        return topicRepository.save(topic).block();
    }

    private static OidcLoginMutator organiser() {
        return mockOidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ORGANISER"));
    }

    private static OidcLoginMutator standardUser() {
        return mockOidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}

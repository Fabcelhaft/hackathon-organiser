package net.fabcelhaft.hackathonorganiser.topics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsRepository;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantRepository;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.skill.Skill;
import net.fabcelhaft.hackathonorganiser.skill.SkillRepository;
import net.fabcelhaft.hackathonorganiser.topic.Topic;
import net.fabcelhaft.hackathonorganiser.topic.TopicApprovalStatus;
import net.fabcelhaft.hackathonorganiser.topic.TopicRepository;
import net.fabcelhaft.hackathonorganiser.topic.TopicService;
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
 * Integration tests for User Story 3's Topic browse/propose/edit self-service (T021;
 * contracts/topics-self-service-and-approval.md): the homepage topic list shows name, description,
 * and author display-name+OIDC-subject (FR-009); propose creates a Topic authored by the current
 * Participant, starting Pending/Approved per the current setting (FR-013); any authenticated
 * Standard user may propose, regardless of Participant status; the author can edit their own
 * Topic, a non-author cannot (FR-011); the author's
 * own Pending Topic sorts to the top labeled "Pending approval" (FR-009a, FR-012b); a Pending
 * Topic is invisible to any other non-Organiser viewer (FR-012a); a blank field re-renders the
 * propose form with a field-associated error (FR-037).
 */
@SpringBootTest
@Testcontainers
class TopicSelfServiceManagementIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @Autowired
    ApplicationContext applicationContext;

    WebTestClient webTestClient;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ParticipantRepository participantRepository;

    @Autowired
    TopicRepository topicRepository;

    @Autowired
    OrganiserSettingsRepository organiserSettingsRepository;

    @Autowired
    SkillRepository skillRepository;

    @Autowired
    TopicService topicService;

    @Autowired
    DatabaseClient databaseClient;

    @BeforeEach
    void setUpWebTestClient() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    /**
     * The Home Page's fullness-sorted rows and own-Topic pinning (Story 10) are both computed over
     * every Topic in the database — without this cleanup, Topics accumulated from earlier tests in
     * this class would compete for the 10-row cap, mirroring the same fix already applied to {@code
     * HomeControllerIT}. Deleted in FK-dependency order (no {@code ON DELETE CASCADE} in schema.sql).
     */
    @BeforeEach
    void resetTopicsAndGroupsBetweenTests() {
        databaseClient.sql("DELETE FROM group_members").then().block();
        databaseClient.sql("DELETE FROM groups").then().block();
        databaseClient.sql("DELETE FROM topic_skills").then().block();
        databaseClient.sql("DELETE FROM topics").then().block();
    }

    @BeforeEach
    void resetOrganiserSettingsToDefaults() {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setSelfRegistrationEnabled(true);
                    settings.setSelfRevocationEnabled(true);
                    settings.setTopicApprovalRequired(false);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    @Test
    void homepageTopicTableShowsNameAndParticipantCount() {
        // Feature 005 (FR-003, FR-004): the Home Page table shows Name/participant count/Skills
        // you offer only — author and description moved to GET /topics/overview (US5).
        User author = persistUser(false);
        persistParticipant(author.getId());
        Topic topic = persistTopic(author.getId(), "Robotics", "Build a robot", TopicApprovalStatus.APPROVED);

        String body = homeBody(author);

        assertThat(body).contains(topic.getName());
    }

    @Test
    void proposeCreatesATopicAuthoredByTheCurrentParticipantStartingApprovedWhenApprovalNotRequired() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        String name = "Proposed Topic " + UUID.randomUUID();

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics")
                .body(BodyInserters.fromFormData("name", name).with("description", "A description"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        Topic saved = findByName(name);
        assertThat(saved.getCreatedByUserId()).isEqualTo(author.getId());
        assertThat(saved.getApprovalStatus()).isEqualTo(TopicApprovalStatus.APPROVED);
    }

    @Test
    void proposeStartsPendingWhenTopicApprovalIsRequired() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        enableTopicApprovalRequired();
        String name = "Pending Proposed Topic " + UUID.randomUUID();

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics")
                .body(BodyInserters.fromFormData("name", name).with("description", "A description"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(findByName(name).getApprovalStatus()).isEqualTo(TopicApprovalStatus.PENDING);
    }

    @Test
    void standardUserWithNoParticipantRecordCanProposeTopic() {
        User standardUser = persistUser(false);
        String name = "No Participant Record Topic " + UUID.randomUUID();

        webTestClient
                .mutateWith(loginAs(standardUser))
                .get()
                .uri("/topics/new")
                .exchange()
                .expectStatus()
                .isOk();

        webTestClient
                .mutateWith(loginAs(standardUser))
                .post()
                .uri("/topics")
                .body(BodyInserters.fromFormData("name", name).with("description", "A description"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(findByName(name).getCreatedByUserId()).isEqualTo(standardUser.getId());
    }

    @Test
    void authorCanEditTheirOwnTopicButANonAuthorCannot() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        User otherUser = persistUser(false);
        persistParticipant(otherUser.getId());
        Topic topic = persistTopic(author.getId(), "Old Name", "Old Desc", TopicApprovalStatus.APPROVED);

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics/{id}", topic.getId())
                .body(BodyInserters.fromFormData("name", "New Name").with("description", "New Desc"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        Topic updated = topicRepository.findById(topic.getId()).block();
        assertThat(updated.getName()).isEqualTo("New Name");
        assertThat(updated.getDescription()).isEqualTo("New Desc");

        webTestClient
                .mutateWith(loginAs(otherUser))
                .get()
                .uri("/topics/{id}/edit", topic.getId())
                .exchange()
                .expectStatus()
                .isForbidden();

        webTestClient
                .mutateWith(loginAs(otherUser))
                .post()
                .uri("/topics/{id}", topic.getId())
                .body(BodyInserters.fromFormData("name", "Hijacked").with("description", "Hijacked"))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void aPendingTopicNeverAppearsOnTheHomePageForAnyoneOtherThanItsOwnAuthor() {
        // Feature 005 (spec Assumptions, updated 2026-08-30 — FR-033, Story 10): a Pending Topic is
        // still invisible on the Home Page to everyone except its author, but its author now sees
        // it pinned above the fullness-sorted rows (see
        // aPendingTopicIsPinnedAboveTheFullnessSortedRowsForItsOwnAuthorButNeverShowsAJoinAction
        // below) — it just never appears there for anyone else, mirroring GET /topics/overview's
        // existing Pending-visibility rule.
        User author = persistUser(false);
        persistParticipant(author.getId());
        User otherViewer = persistUser(false);
        persistParticipant(otherViewer.getId());
        Topic pending = persistTopic(author.getId(), "My Pending Topic", "Desc", TopicApprovalStatus.PENDING);

        assertThat(homeBody(otherViewer)).doesNotContain(pending.getName());
    }

    @Test
    void aPendingTopicIsPinnedAboveTheFullnessSortedRowsForItsOwnAuthorButNeverShowsAJoinAction() {
        // FR-033, FR-035, Story 10: the author's own Pending Topic is pinned above the
        // fullness-sorted rows on the Home Page, but — unlike an Approved pinned Topic — it never
        // offers a "Join" action, since a Pending Topic cannot be joined.
        User author = persistUser(false);
        persistParticipant(author.getId());
        Topic pending = persistTopic(author.getId(), "My Pending Topic", "Desc", TopicApprovalStatus.PENDING);

        String body = homeBody(author);

        assertThat(body).contains(pending.getName());
        assertThat(body).doesNotContain("/topics/" + pending.getId() + "/join");
    }

    @Test
    void pendingTopicIsInvisibleToAnyOtherNonOrganiserViewer() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        User otherViewer = persistUser(false);
        persistParticipant(otherViewer.getId());
        Topic pending =
                persistTopic(author.getId(), "Hidden Pending Topic " + UUID.randomUUID(), "Desc", TopicApprovalStatus.PENDING);

        String body = homeBody(otherViewer);
        assertThat(body).doesNotContain(pending.getName());

        webTestClient
                .mutateWith(loginAs(otherViewer))
                .get()
                .uri("/topics/{id}/edit", pending.getId())
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void proposeWithBlankNameOrDescriptionReRendersTheFormWithAFieldAssociatedError() {
        User author = persistUser(false);
        persistParticipant(author.getId());

        String body = webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics")
                .body(BodyInserters.fromFormData("name", "").with("description", "Some description"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("required");
    }

    // --- Skill selections on propose/edit (Story 1, FR-001, FR-002) -----------------------------

    @Test
    void proposeWithSkillIdsCreatesTheTopicWithExactlyThoseSkillsAttached() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        Skill python = persistSkill("Python");
        Skill rust = persistSkill("Rust");
        String name = "Skilled Topic " + UUID.randomUUID();

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics")
                .body(BodyInserters.fromFormData("name", name)
                        .with("description", "A description")
                        .with("skillIds", python.getId().toString())
                        .with("skillIds", rust.getId().toString()))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        Topic saved = findByName(name);
        String editBody = webTestClient
                .mutateWith(loginAs(author))
                .get()
                .uri("/topics/{id}/edit", saved.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(editBody).containsPattern("(?s)value=\"" + python.getId() + "\".*?checked=\"checked\"");
        assertThat(editBody).containsPattern("(?s)value=\"" + rust.getId() + "\".*?checked=\"checked\"");
    }

    @Test
    void proposeWithNoSkillIdsSucceedsWithAnEmptySkillList() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        String name = "No Skill Topic " + UUID.randomUUID();

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics")
                .body(BodyInserters.fromFormData("name", name).with("description", "A description"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        Topic saved = findByName(name);
        assertThat(topicService.findDetail(saved.getId()).block().skillIds()).isEmpty();
    }

    @Test
    void proposeWithAnUnknownSkillIdIsRejectedAndPreservesTheSubmittedSelection() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        Skill python = persistSkill("Python " + UUID.randomUUID());
        UUID unknownSkillId = UUID.randomUUID();

        String body = webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics")
                .body(BodyInserters.fromFormData("name", "Unknown Skill Topic")
                        .with("description", "A description")
                        .with("skillIds", python.getId().toString())
                        .with("skillIds", unknownSkillId.toString()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).containsPattern("(?s)value=\"" + python.getId() + "\".*?checked=\"checked\"");
    }

    @Test
    void updateReplacesTheSkillSetAddingAndRemoving() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        Skill python = persistSkill("Python " + UUID.randomUUID());
        Skill rust = persistSkill("Rust " + UUID.randomUUID());
        Topic topic = persistTopic(author.getId(), "Old Name", "Old Desc", TopicApprovalStatus.APPROVED);
        topicService
                .updateAsAuthor(topic.getId(), author.getId(), "Old Name", "Old Desc", List.of(python.getId()))
                .block();

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics/{id}", topic.getId())
                .body(BodyInserters.fromFormData("name", "New Name")
                        .with("description", "New Desc")
                        .with("skillIds", rust.getId().toString()))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        List<UUID> skillIds = topicService.findDetail(topic.getId()).block().skillIds();
        assertThat(skillIds).containsExactly(rust.getId());
    }

    // --- Test helpers ------------------------------------------------------------------------------

    private String homeBody(User user) {
        return webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private Topic findByName(String name) {
        return topicRepository.findAll().filter(t -> t.getName().equals(name)).blockFirst();
    }

    private void enableTopicApprovalRequired() {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setTopicApprovalRequired(true);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    private User persistUser(boolean organiser) {
        User user = new User();
        user.setOidcSubject("sub-" + UUID.randomUUID());
        user.setDisplayName("User " + UUID.randomUUID());
        user.setEmail("user-" + UUID.randomUUID() + "@example.com");
        user.setOrganiser(organiser);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).block();
    }

    private Participant persistParticipant(UUID userId) {
        Participant participant = new Participant();
        participant.setUserId(userId);
        participant.setStatus(ParticipantStatus.ACTIVE);
        Instant now = Instant.now();
        participant.setCreatedAt(now);
        participant.setUpdatedAt(now);
        return participantRepository.save(participant).block();
    }

    private Skill persistSkill(String name) {
        Skill skill = new Skill();
        skill.setName(name);
        Instant now = Instant.now();
        skill.setCreatedAt(now);
        skill.setUpdatedAt(now);
        return skillRepository.save(skill).block();
    }

    private Topic persistTopic(UUID creatorUserId, String name, String description, TopicApprovalStatus status) {
        Topic topic = new Topic();
        topic.setName(name);
        topic.setDescription(description);
        topic.setCreatedByUserId(creatorUserId);
        topic.setApprovalStatus(status);
        Instant now = Instant.now();
        topic.setCreatedAt(now);
        topic.setUpdatedAt(now);
        return topicRepository.save(topic).block();
    }

    private static OidcLoginMutator loginAs(User user) {
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
        HackathonOidcUser principal = new HackathonOidcUser(user, delegate);
        return mockOidcLogin().oidcUser(principal);
    }
}

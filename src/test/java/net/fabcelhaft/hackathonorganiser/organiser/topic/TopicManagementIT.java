package net.fabcelhaft.hackathonorganiser.organiser.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupRepository;
import net.fabcelhaft.hackathonorganiser.group.GroupStatus;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantRepository;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
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
 * Integration tests for User Story 4's Topic management (T043), covering
 * specs/002-core-domain-model/contracts/topic-management.md end to end against the real
 * {@code SecurityWebFilterChain} and repositories — no hand-mocked security substitute
 * (research.md §6), following the same {@code WebTestClient} + {@code mockOidcLogin()} +
 * Testcontainers pattern as the other {@code organiser.*} integration tests in this feature.
 */
@SpringBootTest
@Testcontainers
class TopicManagementIT {

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
    UserRepository userRepository;

    @Autowired
    TopicRepository topicRepository;

    @Autowired
    SkillRepository skillRepository;

    @Autowired
    GroupRepository groupRepository;

    @Autowired
    net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsRepository organiserSettingsRepository;

    @Autowired
    ParticipantRepository participantRepository;

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

    // --- Create (FR-015) --------------------------------------------------------------------------

    @Test
    void organiserCanCreateATopicWithNameDescriptionCreatorAndSkills() {
        User creator = persistUser("Creator " + UUID.randomUUID());
        Skill skillA = persistSkill("Skill A " + UUID.randomUUID());
        String name = "Topic " + UUID.randomUUID();

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/topics")
                .body(BodyInserters.fromFormData("name", name)
                        .with("description", "A description")
                        .with("created_by_user_id", creator.getId().toString())
                        .with("skill_ids", skillA.getId().toString()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        Topic saved = topicRepository.findAll()
                .filter(t -> t.getName().equals(name))
                .blockFirst();
        assertThat(saved).isNotNull();
        assertThat(saved.getDescription()).isEqualTo("A description");
        assertThat(saved.getCreatedByUserId()).isEqualTo(creator.getId());

        String body = detailBody(saved.getId());
        assertThat(body).contains(creator.getDisplayName());
        assertThat(body).contains(skillA.getName());
    }

    @Test
    void creationRejectsMissingName() {
        User creator = persistUser("Missing Name Creator " + UUID.randomUUID());

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/topics")
                .body(BodyInserters.fromFormData("description", "Desc")
                        .with("created_by_user_id", creator.getId().toString()))
                .exchange()
                .expectStatus().isOk(); // form re-rendered with error, not redirected
    }

    @Test
    void creationRejectsMissingDescription() {
        User creator = persistUser("Missing Description Creator " + UUID.randomUUID());

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/topics")
                .body(BodyInserters.fromFormData("name", "Name " + UUID.randomUUID())
                        .with("created_by_user_id", creator.getId().toString()))
                .exchange()
                .expectStatus().isOk(); // form re-rendered with error, not redirected
    }

    @Test
    void creationRejectsMissingCreatedByUserId() {
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/topics")
                .body(BodyInserters.fromFormData("name", "Name " + UUID.randomUUID())
                        .with("description", "Desc"))
                .exchange()
                .expectStatus().isOk(); // form re-rendered with error, not redirected
    }

    @Test
    void creationRejectsAnUnknownCreatedByUserId() {
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/topics")
                .body(BodyInserters.fromFormData("name", "Name " + UUID.randomUUID())
                        .with("description", "Desc")
                        .with("created_by_user_id", UUID.randomUUID().toString()))
                .exchange()
                .expectStatus().isOk(); // form re-rendered with error, not redirected
    }

    // --- View (detail/edit) -------------------------------------------------------------------------

    @Test
    void organiserCanViewTopicDetailAndEditForm() {
        User creator = persistUser("Detail Creator " + UUID.randomUUID());
        Topic topic = persistTopic(creator.getId(), "Detail Topic " + UUID.randomUUID(), "Desc");

        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/topics/{id}", topic.getId())
                .exchange()
                .expectStatus().isOk();

        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/topics/{id}/edit", topic.getId())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void unknownTopicDetailAndEditReturnNotFound() {
        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/topics/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/topics/{id}/edit", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void organiserCanViewTheNewTopicForm() {
        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/topics/new")
                .exchange()
                .expectStatus().isOk();
    }

    // --- Edit / Skill associations (FR-010) ----------------------------------------------------------

    @Test
    void organiserCanEditNameDescriptionAndReplaceSkillAssociations() {
        User creator = persistUser("Edit Creator " + UUID.randomUUID());
        Topic topic = persistTopic(creator.getId(), "Old Name " + UUID.randomUUID(), "Old Desc");
        Skill skillA = persistSkill("Edit Skill A " + UUID.randomUUID());
        Skill skillB = persistSkill("Edit Skill B " + UUID.randomUUID());

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/topics/{id}", topic.getId())
                .body(BodyInserters.fromFormData("name", "New Name")
                        .with("description", "New Desc")
                        .with("skill_ids", skillA.getId().toString()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        String bodyAfterFirst = detailBody(topic.getId());
        assertThat(bodyAfterFirst).contains("New Name");
        assertThat(bodyAfterFirst).contains("New Desc");
        assertThat(bodyAfterFirst).contains(skillA.getName());

        // Replace the selection entirely with skillB.
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/topics/{id}", topic.getId())
                .body(BodyInserters.fromFormData("name", "New Name")
                        .with("description", "New Desc")
                        .with("skill_ids", skillB.getId().toString()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        String bodyAfterReplace = detailBody(topic.getId());
        assertThat(bodyAfterReplace).contains(skillB.getName());
        assertThat(bodyAfterReplace).doesNotContain(skillA.getName());
    }

    @Test
    void updatingAnUnknownTopicReturnsNotFound() {
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/topics/{id}", UUID.randomUUID())
                .body(BodyInserters.fromFormData("name", "Name").with("description", "Desc"))
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- Creator persistence (FR-015) ---------------------------------------------------------------

    @Test
    void updateRouteReassignsTheCreatorWhenACreatedByUserIdIsSubmitted() {
        // Feature 003's FR-015 supersedes 002's original immutability guarantee for this one
        // Organiser-only route (data-model.md "Topic", TopicManagementIT extended for T030): a
        // submitted created_by_user_id is now applied via TopicService.reassignAuthor.
        User originalCreator = persistUser("Original Creator " + UUID.randomUUID());
        User newCreator = persistUser("New Creator " + UUID.randomUUID());
        Topic topic = persistTopic(originalCreator.getId(), "Reassignable Creator Topic " + UUID.randomUUID(), "Desc");

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/topics/{id}", topic.getId())
                .body(BodyInserters.fromFormData("name", "Reassignable Creator Topic")
                        .with("description", "Desc")
                        .with("created_by_user_id", newCreator.getId().toString()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(topicRepository.findById(topic.getId()).block().getCreatedByUserId())
                .isEqualTo(newCreator.getId());
    }

    @Test
    void updateRouteRejectsAnUnknownReassignedCreatorWithAFieldAssociatedError() {
        User originalCreator = persistUser("Original Creator " + UUID.randomUUID());
        Topic topic = persistTopic(originalCreator.getId(), "Unknown Reassign Topic " + UUID.randomUUID(), "Desc");

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/topics/{id}", topic.getId())
                .body(BodyInserters.fromFormData("name", "Unknown Reassign Topic")
                        .with("description", "Desc")
                        .with("created_by_user_id", UUID.randomUUID().toString()))
                .exchange()
                .expectStatus().isOk(); // form re-rendered with error, not a bare 404

        assertThat(topicRepository.findById(topic.getId()).block().getCreatedByUserId())
                .isEqualTo(originalCreator.getId());
    }

    @Test
    void updateRouteWithNoCreatedByUserIdFieldLeavesTheCreatorUnchanged() {
        User originalCreator = persistUser("Unchanged Creator " + UUID.randomUUID());
        Topic topic = persistTopic(originalCreator.getId(), "Unchanged Creator Topic " + UUID.randomUUID(), "Desc");

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/topics/{id}", topic.getId())
                .body(BodyInserters.fromFormData("name", "Unchanged Creator Topic").with("description", "Desc"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(topicRepository.findById(topic.getId()).block().getCreatedByUserId())
                .isEqualTo(originalCreator.getId());
    }

    @Test
    void creatorReferenceIsRetainedAfterCreatorsAccessIsLaterRevoked() {
        User creator = persistUser("Revoked Creator " + UUID.randomUUID());
        Topic topic = persistTopic(creator.getId(), "Revoke Test Topic " + UUID.randomUUID(), "Desc");

        // Simulate the creator's Organiser privilege being revoked (edge case, spec.md) — the
        // User row itself is never removed, only its `organiser` flag flips.
        creator.setOrganiser(false);
        creator.setUpdatedAt(Instant.now());
        userRepository.save(creator).block();

        String body = detailBody(topic.getId());
        assertThat(body).contains(creator.getDisplayName());
        assertThat(topicRepository.findById(topic.getId()).block().getCreatedByUserId())
                .isEqualTo(creator.getId());
    }

    // --- List/detail views show the real active-Group status (User Story 5, group-management.md) ---

    @Test
    void listAndDetailViewsShowNoActiveGroupWhenTheTopicHasNone() {
        User creator = persistUser("No Group Creator " + UUID.randomUUID());
        Topic topic = persistTopic(creator.getId(), "No Group Topic " + UUID.randomUUID(), "Desc");

        String listBody = webTestClient.mutateWith(organiser())
                .get().uri("/organiser/topics")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(listBody).contains("No active group");

        assertThat(detailBody(topic.getId())).contains("No active group");
    }

    @Test
    void listAndDetailViewsShowTheRealActiveGroupOnceOneExists() {
        User creator = persistUser("Group Status Creator " + UUID.randomUUID());
        Topic topic = persistTopic(creator.getId(), "Group Status Topic " + UUID.randomUUID(), "Desc");
        Group group = persistGroup(topic.getId());

        String listBody = webTestClient.mutateWith(organiser())
                .get().uri("/organiser/topics")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(listBody).contains("/organiser/groups/" + group.getId());

        assertThat(detailBody(topic.getId())).contains("/organiser/groups/" + group.getId());
    }

    // --- Non-Organiser denied on every route (FR-022, SC-004) -----------------------------------

    @Test
    void nonOrganiserIsDeniedOnEveryRoute() {
        User creator = persistUser("Guarded Creator " + UUID.randomUUID());
        Topic topic = persistTopic(creator.getId(), "Guarded Topic " + UUID.randomUUID(), "Desc");

        webTestClient.mutateWith(standardUser())
                .get().uri("/organiser/topics")
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .get().uri("/organiser/topics/new")
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/topics")
                .body(BodyInserters.fromFormData("name", "Nope")
                        .with("description", "Nope")
                        .with("created_by_user_id", creator.getId().toString()))
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .get().uri("/organiser/topics/{id}", topic.getId())
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .get().uri("/organiser/topics/{id}/edit", topic.getId())
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/topics/{id}", topic.getId())
                .body(BodyInserters.fromFormData("name", "Nope").with("description", "Nope"))
                .exchange()
                .expectStatus().isForbidden();
    }

    // --- Topic approval workflow (User Story 4, T030; FR-013, FR-014, FR-016) -------------------

    @Test
    void enablingTopicApprovalRequiredMakesNewlyProposedTopicsStartPending() {
        User organiser = persistUser("Approval Organiser " + UUID.randomUUID());
        organiser.setOrganiser(true);
        userRepository.save(organiser).block();
        User author = persistUser("Approval Author " + UUID.randomUUID());
        persistParticipant(author.getId());
        setTopicApprovalRequired(true);
        String name = "Pending Via Setting " + UUID.randomUUID();

        webTestClient.mutateWith(loginAsUser(author))
                .post().uri("/topics")
                .body(BodyInserters.fromFormData("name", name).with("description", "Desc"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        Topic saved = topicRepository.findAll().filter(t -> t.getName().equals(name)).blockFirst();
        assertThat(saved.getApprovalStatus())
                .isEqualTo(net.fabcelhaft.hackathonorganiser.topic.TopicApprovalStatus.PENDING);
    }

    @Test
    void disablingTopicApprovalRequiredIsNotRetroactive() {
        User author = persistUser("Non Retroactive Author " + UUID.randomUUID());
        persistParticipant(author.getId());
        setTopicApprovalRequired(true);
        String name = "Stays Pending " + UUID.randomUUID();
        webTestClient.mutateWith(loginAsUser(author))
                .post().uri("/topics")
                .body(BodyInserters.fromFormData("name", name).with("description", "Desc"))
                .exchange();

        setTopicApprovalRequired(false);

        Topic stillPending = topicRepository.findAll().filter(t -> t.getName().equals(name)).blockFirst();
        assertThat(stillPending.getApprovalStatus())
                .isEqualTo(net.fabcelhaft.hackathonorganiser.topic.TopicApprovalStatus.PENDING);
    }

    @Test
    void organiserCanApproveAPendingTopic() {
        User creator = persistUser("Pending Approve Creator " + UUID.randomUUID());
        Topic pending = persistTopic(creator.getId(), "To Approve " + UUID.randomUUID(), "Desc");
        pending.setApprovalStatus(net.fabcelhaft.hackathonorganiser.topic.TopicApprovalStatus.PENDING);
        topicRepository.save(pending).block();

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/topics/{id}/approve", pending.getId())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(topicRepository.findById(pending.getId()).block().getApprovalStatus())
                .isEqualTo(net.fabcelhaft.hackathonorganiser.topic.TopicApprovalStatus.APPROVED);
    }

    @Test
    void approvingAnUnknownTopicReturnsNotFound() {
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/topics/{id}/approve", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void multiplePendingTopicsFromDifferentAuthorsAppearGroupedTogetherOrderedByCreationDateInTheOrganisersView() {
        User organiserUser = persistUser("Grouped Organiser " + UUID.randomUUID());
        organiserUser.setOrganiser(true);
        userRepository.save(organiserUser).block();
        User authorA = persistUser("Grouped Author A " + UUID.randomUUID());
        User authorB = persistUser("Grouped Author B " + UUID.randomUUID());
        Topic pendingA = persistTopic(authorA.getId(), "Grouped Pending A " + UUID.randomUUID(), "Desc");
        pendingA.setApprovalStatus(net.fabcelhaft.hackathonorganiser.topic.TopicApprovalStatus.PENDING);
        topicRepository.save(pendingA).block();
        Topic pendingB = persistTopic(authorB.getId(), "Grouped Pending B " + UUID.randomUUID(), "Desc");
        pendingB.setApprovalStatus(net.fabcelhaft.hackathonorganiser.topic.TopicApprovalStatus.PENDING);
        topicRepository.save(pendingB).block();

        String body = webTestClient.mutateWith(loginAsUser(organiserUser))
                .get().uri("/")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains(pendingA.getName());
        assertThat(body).contains(pendingB.getName());
        assertThat(body.indexOf(pendingA.getName())).isLessThan(body.indexOf(pendingB.getName()));
    }

    // --- Test helpers ----------------------------------------------------------------------------

    private String detailBody(UUID topicId) {
        return webTestClient.mutateWith(organiser())
                .get().uri("/organiser/topics/{id}", topicId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private User persistUser(String displayName) {
        User user = new User();
        user.setOidcSubject("sub-" + UUID.randomUUID());
        user.setDisplayName(displayName);
        user.setEmail(displayName.toLowerCase().replace(' ', '.') + "@example.com");
        user.setOrganiser(false);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).block();
    }

    private Topic persistTopic(UUID creatorId, String name, String description) {
        Topic topic = new Topic();
        topic.setName(name);
        topic.setDescription(description);
        topic.setCreatedByUserId(creatorId);
        topic.setApprovalStatus(net.fabcelhaft.hackathonorganiser.topic.TopicApprovalStatus.APPROVED);
        topic.setCreatedAt(Instant.now());
        topic.setUpdatedAt(Instant.now());
        return topicRepository.save(topic).block();
    }

    private Skill persistSkill(String name) {
        Skill skill = new Skill();
        skill.setName(name);
        skill.setCreatedAt(Instant.now());
        skill.setUpdatedAt(Instant.now());
        return skillRepository.save(skill).block();
    }

    private Group persistGroup(UUID topicId) {
        Group group = new Group();
        group.setTopicId(topicId);
        group.setStatus(GroupStatus.ACTIVE);
        group.setCreatedAt(Instant.now());
        group.setUpdatedAt(Instant.now());
        return groupRepository.save(group).block();
    }

    private static OidcLoginMutator organiser() {
        return mockOidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ORGANISER"));
    }

    private static OidcLoginMutator standardUser() {
        return mockOidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER"));
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

    private void setTopicApprovalRequired(boolean required) {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setTopicApprovalRequired(required);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    /** A full {@link HackathonOidcUser} principal login — needed for routes (Home, Topic
     * self-service) that resolve {@code @AuthenticationPrincipal HackathonOidcUser}, unlike the
     * plain {@link #organiser()}/{@link #standardUser()} mutators this 002-era file otherwise
     * uses for the {@code /organiser/topics/**} routes, which never inject the principal. */
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
        HackathonOidcUser principal = new HackathonOidcUser(user, delegate);
        return mockOidcLogin().oidcUser(principal);
    }
}

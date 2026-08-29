package net.fabcelhaft.hackathonorganiser.organiser.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupRepository;
import net.fabcelhaft.hackathonorganiser.group.GroupStatus;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
    void updateRouteIgnoresAnyAttemptToChangeTheCreator() {
        User originalCreator = persistUser("Original Creator " + UUID.randomUUID());
        User otherUser = persistUser("Other User " + UUID.randomUUID());
        Topic topic = persistTopic(originalCreator.getId(), "Immutable Creator Topic " + UUID.randomUUID(), "Desc");

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/topics/{id}", topic.getId())
                .body(BodyInserters.fromFormData("name", "Immutable Creator Topic")
                        .with("description", "Desc")
                        .with("created_by_user_id", otherUser.getId().toString()))
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
}

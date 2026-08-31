package net.fabcelhaft.hackathonorganiser.organiser.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupRepository;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
import net.fabcelhaft.hackathonorganiser.group.GroupStatus;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsRepository;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantRepository;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
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
 * Integration tests for User Story 5's Group management (T051), covering
 * specs/002-core-domain-model/contracts/group-management.md end to end against the real {@code
 * SecurityWebFilterChain} and repositories — no hand-mocked security substitute (research.md §6),
 * following the same {@code WebTestClient} + {@code mockOidcLogin()} + Testcontainers pattern as
 * the other {@code organiser.*} integration tests in this feature.
 */
@SpringBootTest
@Testcontainers
class GroupManagementIT {

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
    ParticipantRepository participantRepository;

    @Autowired
    GroupRepository groupRepository;

    @Autowired
    DatabaseClient databaseClient;

    @Autowired
    GroupService groupService;

    @Autowired
    OrganiserSettingsRepository organiserSettingsRepository;

    @BeforeEach
    void resetMaxGroupMembers() {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setMaxGroupMembers(5);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    // --- Create (FR-016a) --------------------------------------------------------------------------

    @Test
    void organiserCanCreateAGroupForATopicWithNoActiveGroup() {
        Topic topic = persistTopic("Create Group Topic " + UUID.randomUUID());

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/groups")
                .body(BodyInserters.fromFormData("topic_id", topic.getId().toString()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        Group saved = groupRepository
                .findByTopicIdAndStatus(topic.getId(), GroupStatus.ACTIVE)
                .block();
        assertThat(saved).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(GroupStatus.ACTIVE);

        assertThat(detailBody(saved.getId())).contains(topic.getName());
    }

    @Test
    void creationRejectsASecondActiveGroupForATopicThatAlreadyHasOne() {
        Topic topic = persistTopic("Duplicate Group Topic " + UUID.randomUUID());
        persistActiveGroup(topic.getId());

        String body = webTestClient
                .mutateWith(organiser())
                .post().uri("/organiser/groups")
                .body(BodyInserters.fromFormData("topic_id", topic.getId().toString()))
                .exchange()
                .expectStatus().isOk() // form re-rendered with error, not redirected
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("already has a Group");
    }

    @Test
    void creationRejectsAnUnknownTopicId() {
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/groups")
                .body(BodyInserters.fromFormData("topic_id", UUID.randomUUID().toString()))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void organiserCanViewTheGroupListAndNewGroupForm() {
        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/groups")
                .exchange()
                .expectStatus().isOk();

        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/groups/new")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void newGroupFormsTopicPickerExcludesTopicsThatAlreadyHaveAnActiveGroup() {
        Topic withGroup = persistTopic("Already Has Group " + UUID.randomUUID());
        persistActiveGroup(withGroup.getId());
        Topic withoutGroup = persistTopic("No Group Yet " + UUID.randomUUID());

        String body = webTestClient.mutateWith(organiser())
                .get().uri("/organiser/groups/new")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains(withoutGroup.getName());
        assertThat(body).doesNotContain(withGroup.getName());
    }

    @Test
    void unknownGroupDetailReturnsNotFound() {
        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/groups/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- Member add/remove (FR-017) -----------------------------------------------------------------

    @Test
    void organiserCanAddAndRemoveAMember() {
        Topic topic = persistTopic("Member Topic " + UUID.randomUUID());
        Group group = persistActiveGroup(topic.getId());
        Participant participant = persistParticipant("Member Participant " + UUID.randomUUID());

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/groups/{id}/members", group.getId())
                .body(BodyInserters.fromFormData("participant_id", participant.getId().toString()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(detailBody(group.getId())).contains("Member Participant");

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/groups/{id}/members/{pid}/remove", group.getId(), participant.getId())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        // Former member: still viewable (FR-016b), but no longer an active member — remove
        // again must now 404 since the membership is no longer active.
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/groups/{id}/members/{pid}/remove", group.getId(), participant.getId())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void addMemberRejectsAnUnknownParticipantId() {
        Topic topic = persistTopic("Unknown Participant Topic " + UUID.randomUUID());
        Group group = persistActiveGroup(topic.getId());

        String body = webTestClient
                .mutateWith(organiser())
                .post().uri("/organiser/groups/{id}/members", group.getId())
                .body(BodyInserters.fromFormData("participant_id", UUID.randomUUID().toString()))
                .exchange()
                .expectStatus().isOk() // detail re-rendered with error, not redirected
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).containsIgnoringCase("unknown");
    }

    @Test
    void addMemberRejectsAParticipantAlreadyInADifferentActiveGroup() {
        Topic topicA = persistTopic("Group A Topic " + UUID.randomUUID());
        Topic topicB = persistTopic("Group B Topic " + UUID.randomUUID());
        Group groupA = persistActiveGroup(topicA.getId());
        Group groupB = persistActiveGroup(topicB.getId());
        Participant participant = persistParticipant("Double Booked Participant " + UUID.randomUUID());
        addMemberDirectly(groupA.getId(), participant.getId());

        String body = webTestClient
                .mutateWith(organiser())
                .post().uri("/organiser/groups/{id}/members", groupB.getId())
                .body(BodyInserters.fromFormData("participant_id", participant.getId().toString()))
                .exchange()
                .expectStatus().isOk() // detail re-rendered with error, not redirected
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("already belongs to a different active Group");
    }

    @Test
    void addMemberRejectsOnADisbandedGroup() {
        Topic topic = persistTopic("Disbanded Add Topic " + UUID.randomUUID());
        Group group = persistActiveGroup(topic.getId());
        Participant participant = persistParticipant("Late Participant " + UUID.randomUUID());

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/groups/{id}/disband", group.getId())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        String body = webTestClient
                .mutateWith(organiser())
                .post().uri("/organiser/groups/{id}/members", group.getId())
                .body(BodyInserters.fromFormData("participant_id", participant.getId().toString()))
                .exchange()
                .expectStatus().isOk() // detail re-rendered with error, not redirected
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).containsIgnoringCase("disband");
    }

    @Test
    void memberOperationsOnAnUnknownGroupReturnNotFound() {
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/groups/{id}/members", UUID.randomUUID())
                .body(BodyInserters.fromFormData("participant_id", UUID.randomUUID().toString()))
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/groups/{id}/members/{pid}/remove", UUID.randomUUID(), UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- Disband (FR-016b) --------------------------------------------------------------------------

    @Test
    void disbandFlipsMembershipsInactiveAndTopicBecomesEligibleAgainForAFreshGroup() {
        Topic topic = persistTopic("Disband Topic " + UUID.randomUUID());
        Group firstGroup = persistActiveGroup(topic.getId());
        Participant participant = persistParticipant("Disbanded Group Member " + UUID.randomUUID());
        addMemberDirectly(firstGroup.getId(), participant.getId());

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/groups/{id}/disband", firstGroup.getId())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        Group reloaded = groupRepository.findById(firstGroup.getId()).block();
        assertThat(reloaded.getStatus()).isEqualTo(GroupStatus.DISBANDED);
        assertThat(reloaded.getDisbandedAt()).isNotNull();

        // The disbanded Group and its former members remain viewable (FR-016b).
        String disbandedDetail = detailBody(firstGroup.getId());
        assertThat(disbandedDetail).contains("Disbanded Group Member");
        assertThat(disbandedDetail).contains("DISBANDED");

        // The Topic is now eligible for a brand-new Group (Acceptance Scenario 5, Story 5).
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/groups")
                .body(BodyInserters.fromFormData("topic_id", topic.getId().toString()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        Group secondGroup = groupRepository
                .findByTopicIdAndStatus(topic.getId(), GroupStatus.ACTIVE)
                .block();
        assertThat(secondGroup).isNotNull();
        assertThat(secondGroup.getId()).isNotEqualTo(firstGroup.getId());
    }

    @Test
    void disbandOfAnUnknownGroupReturnsNotFound() {
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/groups/{id}/disband", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void disbandingAnAlreadyDisbandedGroupIsANoOp() {
        Topic topic = persistTopic("Double Disband Topic " + UUID.randomUUID());
        Group group = persistActiveGroup(topic.getId());

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/groups/{id}/disband", group.getId())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/groups/{id}/disband", group.getId())
                .exchange()
                .expectStatus().isOk(); // no-op error, not a second redirect
    }

    // --- Compliance override (Story 7, FR-015, FR-016, SC-006) -------------------------------------

    @Test
    void settingTheOverrideShowsTheOverrideBadgeAndAllowsAJoinBeyondMaximum() {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setMaxGroupMembers(1);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
        Topic topic = persistTopic("Override Topic " + UUID.randomUUID());
        Group group = persistActiveGroup(topic.getId());
        Participant firstMember = persistParticipant("First Member " + UUID.randomUUID());
        addMemberDirectly(group.getId(), firstMember.getId());

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/groups/{id}/compliance-override", group.getId())
                .body(BodyInserters.fromFormData("override", "true"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(detailBody(group.getId())).contains("Compliant (Organiser Override)");

        Participant secondMember = persistParticipant("Second Member " + UUID.randomUUID());
        Group joined = groupService.join(topic.getId(), secondMember.getId()).block();
        assertThat(joined.getId()).isEqualTo(group.getId());
        assertThat(groupService.activeMemberCount(group.getId()).block()).isEqualTo(2);
    }

    @Test
    void removingTheOverrideRevertsTheBadgeAndReenforcesTheMaximum() {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setMaxGroupMembers(1);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
        Topic topic = persistTopic("Revert Override Topic " + UUID.randomUUID());
        Group group = persistActiveGroup(topic.getId());
        Participant firstMember = persistParticipant("Only Member " + UUID.randomUUID());
        addMemberDirectly(group.getId(), firstMember.getId());
        groupService.setComplianceOverride(group.getId(), true).block();

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/groups/{id}/compliance-override", group.getId())
                .body(BodyInserters.fromFormData("override", "false"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(detailBody(group.getId())).doesNotContain("Organiser Override");

        Participant secondMember = persistParticipant("Rejected Member " + UUID.randomUUID());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> groupService.join(topic.getId(), secondMember.getId()).block())
                .hasMessageContaining("full");
    }

    @Test
    void complianceOverrideOnAnUnknownGroupReturns404() {
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/groups/{id}/compliance-override", UUID.randomUUID())
                .body(BodyInserters.fromFormData("override", "true"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void complianceOverrideIsDeniedToANonOrganiser() {
        Topic topic = persistTopic("Guarded Override Topic " + UUID.randomUUID());
        Group group = persistActiveGroup(topic.getId());

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/groups/{id}/compliance-override", group.getId())
                .body(BodyInserters.fromFormData("override", "true"))
                .exchange()
                .expectStatus().isForbidden();
    }

    // --- Non-Organiser denied on every route (FR-022, SC-004) -----------------------------------

    @Test
    void nonOrganiserIsDeniedOnEveryRoute() {
        Topic topic = persistTopic("Guarded Group Topic " + UUID.randomUUID());
        Group group = persistActiveGroup(topic.getId());
        Participant participant = persistParticipant("Guarded Participant " + UUID.randomUUID());

        webTestClient.mutateWith(standardUser())
                .get().uri("/organiser/groups")
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .get().uri("/organiser/groups/new")
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/groups")
                .body(BodyInserters.fromFormData("topic_id", topic.getId().toString()))
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .get().uri("/organiser/groups/{id}", group.getId())
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/groups/{id}/members", group.getId())
                .body(BodyInserters.fromFormData("participant_id", participant.getId().toString()))
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/groups/{id}/members/{pid}/remove", group.getId(), participant.getId())
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/groups/{id}/disband", group.getId())
                .exchange()
                .expectStatus().isForbidden();
    }

    // --- Test helpers ----------------------------------------------------------------------------

    private String detailBody(UUID groupId) {
        return webTestClient.mutateWith(organiser())
                .get().uri("/organiser/groups/{id}", groupId)
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

    private Topic persistTopic(String name) {
        User creator = persistUser("Creator for " + name);
        Topic topic = new Topic();
        topic.setName(name);
        topic.setDescription("Desc");
        topic.setCreatedByUserId(creator.getId());
        topic.setCreatedAt(Instant.now());
        topic.setUpdatedAt(Instant.now());
        return topicRepository.save(topic).block();
    }

    private Participant persistParticipant(String displayName) {
        User user = persistUser(displayName);
        Participant participant = new Participant();
        participant.setUserId(user.getId());
        participant.setStatus(ParticipantStatus.ACTIVE);
        participant.setCreatedAt(Instant.now());
        participant.setUpdatedAt(Instant.now());
        return participantRepository.save(participant).block();
    }

    private Group persistActiveGroup(UUID topicId) {
        Group group = new Group();
        group.setTopicId(topicId);
        group.setStatus(GroupStatus.ACTIVE);
        group.setCreatedAt(Instant.now());
        group.setUpdatedAt(Instant.now());
        return groupRepository.save(group).block();
    }

    private void addMemberDirectly(UUID groupId, UUID participantId) {
        databaseClient
                .sql("INSERT INTO group_members (group_id, participant_id, active, joined_at)"
                        + " VALUES (:gid, :pid, true, :now)")
                .bind("gid", groupId)
                .bind("pid", participantId)
                .bind("now", Instant.now())
                .then()
                .block();
    }

    private static OidcLoginMutator organiser() {
        return mockOidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ORGANISER"));
    }

    private static OidcLoginMutator standardUser() {
        return mockOidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}

package net.fabcelhaft.hackathonorganiser.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.audit.AuditActor;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsRepository;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantRepository;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantService;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration tests for User Story 1's homepage and self-service registration/revocation (T010;
 * contracts/registration-and-status.md) against the real {@code SecurityWebFilterChain} and
 * repositories — no hand-mocked security substitute, following the same {@code WebTestClient} +
 * {@code mockOidcLogin()} + Testcontainers pattern as 002's {@code organiser.*} integration tests.
 */
@SpringBootTest
@Testcontainers
class HomeControllerIT {

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
    GroupService groupService;

    @Autowired
    OrganiserSettingsRepository organiserSettingsRepository;

    @Autowired
    SkillRepository skillRepository;

    @Autowired
    TopicService topicService;

    @Autowired
    ParticipantService participantService;

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
     * Own-Topic pinning (Story 10) and the fullness-sorted "Open Topics" list are both computed
     * over every Topic in the database, not just ones this test created — without this cleanup,
     * Topics accumulated from earlier tests in this class compete for the Home Page's 10-row cap
     * and can push a later test's own Topic out of it, exactly the kind of cross-test pollution
     * {@code ParticipantsDirectoryManagementIT} already guards against for Participants/Custom
     * Fields. Deleted in FK-dependency order (no {@code ON DELETE CASCADE} in schema.sql).
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
                    settings.setMaxGroupMembers(5);
                    settings.setSkillDisplayMode(
                            net.fabcelhaft.hackathonorganiser.organisersettings.SkillDisplayMode.STILL_NEEDED_ONLY);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    // --- GET / -----------------------------------------------------------------------------------

    @Test
    void homepageShowsRegisterAndNoRevokeWhenNoParticipantRecordExists() {
        User user = persistUser();

        String body = homeBody(user);

        assertThat(body).contains("Register");
        assertThat(body).doesNotContain("Revoke Registration");
    }

    @Test
    void homepageShowsStatusAssignedGroupTopicAndRevokeForAnActiveParticipant() {
        User user = persistUser();
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        Topic topic = persistTopic(user.getId());
        groupService.create(topic.getId(), List.of(participant.getId()), new AuditActor(user.getId(), false)).block();

        String body = homeBody(user);

        assertThat(body).contains("ACTIVE");
        assertThat(body).contains(topic.getName());
        assertThat(body).contains("Revoke Registration");
    }

    // --- POST /register ----------------------------------------------------------------------------

    @Test
    void registerCreatesAnActiveParticipantImmediatelyWithNoForm() {
        User user = persistUser();

        webTestClient
                .mutateWith(loginAs(user))
                .post()
                .uri("/register")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        Participant saved = participantRepository.findByUserId(user.getId()).block();
        assertThat(saved).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(ParticipantStatus.ACTIVE);
    }

    @Test
    void doubleSubmitRegisterIsIdempotentAndDoesNotCreateADuplicate() {
        User user = persistUser();

        webTestClient.mutateWith(loginAs(user)).post().uri("/register").exchange();
        webTestClient.mutateWith(loginAs(user)).post().uri("/register").exchange();

        Participant saved = participantRepository.findByUserId(user.getId()).block();
        assertThat(saved).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(ParticipantStatus.ACTIVE);
    }

    @Test
    void registerReactivatesAnExistingRevokedRecordInPlaceRatherThanCreatingANewRow() {
        User user = persistUser();
        Participant revoked = persistParticipant(user.getId(), ParticipantStatus.REVOKED);

        webTestClient
                .mutateWith(loginAs(user))
                .post()
                .uri("/register")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        Participant saved = participantRepository.findByUserId(user.getId()).block();
        assertThat(saved.getId()).isEqualTo(revoked.getId());
        assertThat(saved.getStatus()).isEqualTo(ParticipantStatus.ACTIVE);
    }

    // --- POST /revoke -------------------------------------------------------------------------------

    @Test
    void revokeSetsRevokedShowsRegisterAgainAndRemovesGroupMembershipPreservingHistory() {
        User user = persistUser();
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        Topic topic = persistTopic(user.getId());
        Group group = groupService
                .create(topic.getId(), List.of(participant.getId()), new AuditActor(user.getId(), false))
                .block();

        webTestClient
                .mutateWith(loginAs(user))
                .post()
                .uri("/revoke")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        Participant saved = participantRepository.findById(participant.getId()).block();
        assertThat(saved.getStatus()).isEqualTo(ParticipantStatus.REVOKED);

        assertThat(groupService.findActiveGroupForParticipant(participant.getId()).block()).isNull();
        GroupService.GroupDetail detail =
                groupService.findDetail(group.getId()).block();
        assertThat(detail.currentMembers()).isEmpty();
        assertThat(detail.formerMembers()).hasSize(1);

        String body = homeBody(user);
        assertThat(body).contains("Register");
        assertThat(body).doesNotContain("Revoke Registration");
    }

    @Test
    void registeringAgainAfterRevokeDoesNotRestoreTheRemovedGroupMembership() {
        User user = persistUser();
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        Topic topic = persistTopic(user.getId());
        groupService.create(topic.getId(), List.of(participant.getId()), new AuditActor(user.getId(), false)).block();

        webTestClient.mutateWith(loginAs(user)).post().uri("/revoke").exchange();
        webTestClient.mutateWith(loginAs(user)).post().uri("/register").exchange();

        Participant saved = participantRepository.findById(participant.getId()).block();
        assertThat(saved.getStatus()).isEqualTo(ParticipantStatus.ACTIVE);
        assertThat(groupService.findActiveGroupForParticipant(participant.getId()).block()).isNull();
    }

    // --- Gating: both actions rejected when their setting is disabled (FR-006) ------------------

    @Test
    void registerIsRejectedWhenSelfRegistrationIsDisabledRegardlessOfWhatThePageShowedAtLoad() {
        User user = persistUser();
        disableSelfRegistration();

        webTestClient
                .mutateWith(loginAs(user))
                .post()
                .uri("/register")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(participantRepository.findByUserId(user.getId()).block()).isNull();
    }

    @Test
    void revokeIsRejectedWhenSelfRevocationIsDisabledRegardlessOfWhatThePageShowedAtLoad() {
        User user = persistUser();
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        disableSelfRevocation();

        webTestClient
                .mutateWith(loginAs(user))
                .post()
                .uri("/revoke")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(participantRepository.findById(participant.getId()).block().getStatus())
                .isEqualTo(ParticipantStatus.ACTIVE);
    }

    // --- Home Page topic table (Story 2, FR-003, FR-003a, FR-003b, FR-004) -----------------------

    @Test
    void homeExcludesFullTopicsAndOrdersRemainingByMemberCountDescending() {
        setMaxGroupMembers(2);
        User viewer = persistUser();
        User author = persistUser();
        Participant authorParticipant = persistParticipant(author.getId(), ParticipantStatus.ACTIVE);

        Topic empty = persistTopic(author.getId());
        Topic partiallyFull = persistTopic(author.getId());
        groupService
                .create(partiallyFull.getId(), List.of(authorParticipant.getId()), new AuditActor(author.getId(), false))
                .block();
        Topic full = persistTopic(author.getId());
        User secondMemberUser = persistUser();
        Participant secondMember = persistParticipant(secondMemberUser.getId(), ParticipantStatus.ACTIVE);
        User thirdMemberUser = persistUser();
        Participant thirdMember = persistParticipant(thirdMemberUser.getId(), ParticipantStatus.ACTIVE);
        groupService
                .create(
                        full.getId(),
                        List.of(secondMember.getId(), thirdMember.getId()),
                        new AuditActor(author.getId(), false))
                .block();

        String body = homeBody(viewer);

        assertThat(body).contains(empty.getName());
        assertThat(body).contains(partiallyFull.getName());
        assertThat(body).doesNotContain(full.getName());
        assertThat(body.indexOf(partiallyFull.getName())).isLessThan(body.indexOf(empty.getName()));
    }

    @Test
    void homeIntersectsNeededSkillsWithTheViewersOwnSkillsAndShowsEmptyCellOtherwise() {
        User viewer = persistUser();
        Participant viewerParticipant = persistParticipant(viewer.getId(), ParticipantStatus.ACTIVE);
        User author = persistUser();
        persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        Skill matching = persistSkill("Rust " + UUID.randomUUID());
        Skill nonMatching = persistSkill("Python " + UUID.randomUUID());
        participantService
                .replaceSkills(viewerParticipant.getId(), List.of(matching.getId()), new AuditActor(viewer.getId(), false))
                .block();
        topicService
                .propose(author.getId(), "Topic With Skills " + UUID.randomUUID(), "Desc",
                        List.of(matching.getId(), nonMatching.getId()), new AuditActor(author.getId(), false))
                .block();

        String body = homeBody(viewer);

        assertThat(body).contains(matching.getName());
        assertThat(body).doesNotContain(nonMatching.getName());
    }

    @Test
    void skillDisplayModeChangesTheSkillsColumnOnTheVeryNextView() {
        User viewer = persistUser();
        Participant viewerParticipant = persistParticipant(viewer.getId(), ParticipantStatus.ACTIVE);
        User author = persistUser();
        Participant authorParticipant = persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        Skill covered = persistSkill("Covered " + UUID.randomUUID());
        Skill stillNeeded = persistSkill("StillNeeded " + UUID.randomUUID());
        participantService
                .replaceSkills(
                        viewerParticipant.getId(),
                        List.of(covered.getId(), stillNeeded.getId()),
                        new AuditActor(viewer.getId(), false))
                .block();
        participantService
                .replaceSkills(authorParticipant.getId(), List.of(covered.getId()), new AuditActor(author.getId(), false))
                .block();
        Topic topic = topicService
                .propose(author.getId(), "Mode Topic " + UUID.randomUUID(), "Desc",
                        List.of(covered.getId(), stillNeeded.getId()), new AuditActor(author.getId(), false))
                .block();
        groupService
                .create(topic.getId(), List.of(authorParticipant.getId()), new AuditActor(author.getId(), false))
                .block();

        setSkillDisplayMode("STILL_NEEDED_ONLY");
        String stillNeededOnlyBody = homeBody(viewer);
        assertThat(stillNeededOnlyBody).contains(stillNeeded.getName());
        assertThat(stillNeededOnlyBody).doesNotContain(covered.getName());

        setSkillDisplayMode("ALL_ASSOCIATED");
        String allAssociatedBody = homeBody(viewer);
        assertThat(allAssociatedBody).contains(stillNeeded.getName());
        assertThat(allAssociatedBody).contains(covered.getName());
    }

    // --- Own-Topic pinning, View Details, and dropped "Group" wording (Stories 9, 10, FR-004a, --
    // --- FR-033, FR-035, FR-036) --------------------------------------------------------------

    @Test
    void homePinsTheViewersOwnPendingAndFullTopicsAboveTheFullnessSortedRowsWithNoJoinActionOnEither() {
        setMaxGroupMembers(1);
        User viewer = persistUser();
        Participant viewerParticipant = persistParticipant(viewer.getId(), ParticipantStatus.ACTIVE);
        Topic ownPending = persistTopicWithStatus(viewer.getId(), TopicApprovalStatus.PENDING);
        Topic ownFull = persistTopicWithStatus(viewer.getId(), TopicApprovalStatus.APPROVED);
        groupService
                .create(ownFull.getId(), List.of(viewerParticipant.getId()), new AuditActor(viewer.getId(), false))
                .block();

        String body = homeBody(viewer);

        assertThat(body).contains(ownPending.getName());
        assertThat(body).contains(ownFull.getName());
        assertThat(body).doesNotContain("/topics/" + ownPending.getId() + "/join");
        assertThat(body).doesNotContain("/topics/" + ownFull.getId() + "/join");
    }

    @Test
    void everyHomePageRowOffersAViewDetailsLinkToTheTopicDetailsView() {
        User author = persistUser();
        Topic topic = persistTopic(author.getId());
        User viewer = persistUser();

        String body = homeBody(viewer);

        assertThat(body).contains("/topics/" + topic.getId());
    }

    @Test
    void theHomePageNeverMentionsGroupToANonOrganiserParticipant() {
        User user = persistUser();
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        Topic topic = persistTopic(user.getId());
        groupService.create(topic.getId(), List.of(participant.getId()), new AuditActor(user.getId(), false)).block();

        String body = homeBody(user);

        assertThat(body).doesNotContainIgnoringCase("group");
    }

    private void setSkillDisplayMode(String mode) {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setSkillDisplayMode(
                            net.fabcelhaft.hackathonorganiser.organisersettings.SkillDisplayMode.valueOf(mode));
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
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

    private void setMaxGroupMembers(int maxGroupMembers) {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setMaxGroupMembers(maxGroupMembers);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    private Skill persistSkill(String name) {
        Skill skill = new Skill();
        skill.setName(name);
        Instant now = Instant.now();
        skill.setCreatedAt(now);
        skill.setUpdatedAt(now);
        return skillRepository.save(skill).block();
    }

    private void disableSelfRegistration() {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setSelfRegistrationEnabled(false);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    private void disableSelfRevocation() {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setSelfRevocationEnabled(false);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    private User persistUser() {
        User user = new User();
        user.setOidcSubject("sub-" + UUID.randomUUID());
        user.setDisplayName("User " + UUID.randomUUID());
        user.setEmail("user-" + UUID.randomUUID() + "@example.com");
        user.setOrganiser(false);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).block();
    }

    private Participant persistParticipant(UUID userId, ParticipantStatus status) {
        Participant participant = new Participant();
        participant.setUserId(userId);
        participant.setStatus(status);
        Instant now = Instant.now();
        participant.setCreatedAt(now);
        participant.setUpdatedAt(now);
        return participantRepository.save(participant).block();
    }

    private Topic persistTopic(UUID creatorUserId) {
        Topic topic = new Topic();
        topic.setName("Topic " + UUID.randomUUID());
        topic.setDescription("Description");
        topic.setCreatedByUserId(creatorUserId);
        Instant now = Instant.now();
        topic.setCreatedAt(now);
        topic.setUpdatedAt(now);
        return topicRepository.save(topic).block();
    }

    private Topic persistTopicWithStatus(UUID creatorUserId, TopicApprovalStatus status) {
        Topic topic = new Topic();
        topic.setName("Topic " + UUID.randomUUID());
        topic.setDescription("Description");
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

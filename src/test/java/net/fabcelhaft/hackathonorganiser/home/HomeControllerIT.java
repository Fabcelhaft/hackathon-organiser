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
        databaseClient.sql("DELETE FROM topic_upvotes").then().block();
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
                    settings.setTopicUpvotingEnabled(true);
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

    // Feature 013 (FR-001, FR-002): the per-row "View" button is gone and the Topic name carries the
    // link instead, so a row no longer spends a whole control on navigation it can imply.
    // Feature 013 (FR-015): the Dashboard card is half the page wide and the "Your Skills" column
    // was empty for most rows while squeezing the Topic name. Equivalent coverage of skill display
    // lives in TopicOverviewManagementIT, which keeps its "Needed Skills" column (FR-016), so this
    // removes a duplicated assertion rather than real coverage.
    // Feature 013 (FR-018, FR-019, FR-020): quieting these two must not cost either of them, nor
    // blur which is the primary action.
    @Test
    void theTopicsCardKeepsBothActionsWithProposeTopicAsThePrimaryOne() {
        User viewer = persistUser();

        String body = homeBody(viewer);

        assertThat(body).contains("Propose Topic");
        assertThat(body).contains("All Topics");
        assertThat(body).contains("href=\"/topics/new\"");
        assertThat(body).contains("href=\"/topics/overview\"");
        assertThat(body)
                .withFailMessage("FR-020: the pair must carry the quieter card-action treatment")
                .contains("card-actions");
        assertThat(body)
                .withFailMessage("FR-019: All Topics stays the secondary of the two")
                .containsPattern("href=\"/topics/overview\"[^>]*class=\"outline secondary\"");
    }

    @Test
    void theHomePageNoLongerShowsASkillsColumnForTheViewer() {
        User viewer = persistUser();
        Participant viewerParticipant = persistParticipant(viewer.getId(), ParticipantStatus.ACTIVE);
        User author = persistUser();
        persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        Skill matching = persistSkill("Rust " + UUID.randomUUID());
        participantService
                .replaceSkills(viewerParticipant.getId(), List.of(matching.getId()), new AuditActor(viewer.getId(), false))
                .block();
        topicService
                .propose(author.getId(), "Topic With Skills " + UUID.randomUUID(), "Desc",
                        List.of(matching.getId()), new AuditActor(author.getId(), false))
                .block();

        String body = homeBody(viewer);

        assertThat(body)
                .withFailMessage("FR-015: the Dashboard must not render a skills column")
                .doesNotContain("Your Skills")
                .doesNotContain(matching.getName());
    }

    // FR-017: the empty-state row has to span the table's ACTUAL column count. Feature 013 removed
    // two columns from this table (the View action and Your Skills), and a stale colspan is the
    // kind of thing that renders fine until the day the table is empty.
    @Test
    void theHomePageEmptyStateRowStillSpansTheWholeTable() {
        User viewer = persistUser();

        String body = homeBody(viewer);

        assertThat(body).contains("No open Topics right now.");
        assertThat(body)
                .withFailMessage("Name + Participants + Upvotes = 3 columns for a viewer who cannot join")
                .contains("colspan=\"3\"");
    }

    @Test
    void everyHomePageRowLinksToTheTopicDetailsViewFromTheTopicNameItself() {
        User author = persistUser();
        Topic topic = persistTopic(author.getId());
        User viewer = persistUser();

        String body = homeBody(viewer);

        assertThat(body).contains("/topics/" + topic.getId());
        assertThat(body)
                .withFailMessage("the Topic name must be the link to its detail page (FR-001)")
                .containsPattern("<a[^>]*href=\"/topics/" + topic.getId() + "\"[^>]*>\\s*"
                        + java.util.regex.Pattern.quote(topic.getName()));
    }

    @Test
    void noHomePageRowRendersASeparateViewDetailsControl() {
        User author = persistUser();
        persistTopic(author.getId());
        User viewer = persistUser();

        String body = homeBody(viewer);

        assertThat(body)
                .withFailMessage("FR-002: the separate per-row View control must not be rendered")
                .doesNotContain("View details for")
                .doesNotContain(">View<")
                .doesNotContain(">View Details<");
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

    // --- Upvotes: count/control, Home Page tiebreak, admin toggle (US1, FR-005a, SC-008) ----------

    @Test
    void homeShowsTheUpvoteCountAndTheViewersOwnUpvoteState() {
        User author = persistUser();
        Topic topic = persistTopic(author.getId());
        User voter = persistUser();

        String beforeBody = homeBody(voter);
        assertThat(beforeBody).contains("Upvote");
        assertThat(beforeBody).doesNotContain("Withdraw upvote");

        castUpvote(topic.getId(), voter.getId());

        String afterBody = homeBody(voter);
        assertThat(afterBody).contains("Withdraw upvote");
    }

    // Feature 013 (FR-005, FR-008, FR-008a): same single toggle control as the overview, from the
    // same shared fragment, so the two screens cannot drift apart.
    @Test
    void eachHomePageRowRendersOneToggleVoteControlCarryingItsPressedState() {
        User author = persistUser();
        Topic topic = persistTopic(author.getId());
        User voter = persistUser();

        String before = homeBody(voter);

        assertThat(before).contains("aria-pressed=\"false\"");
        assertThat(before).contains("aria-label=\"Upvote " + topic.getName() + "\"");
        assertThat(before)
                .withFailMessage("exactly one vote form per row (FR-005)")
                .containsOnlyOnce("data-vote-form");

        castUpvote(topic.getId(), voter.getId());
        String after = homeBody(voter);

        assertThat(after).contains("aria-pressed=\"true\"");
        assertThat(after).contains("aria-label=\"Withdraw upvote for " + topic.getName() + "\"");
        assertThat(after).contains("/topics/" + topic.getId() + "/unupvote");
    }

    @Test
    void homeBreaksAMemberCountTieByUpvoteCountWhenUpvotingIsEnabled() {
        User author = persistUser();
        Topic lessUpvoted = persistTopic(author.getId());
        Topic moreUpvoted = persistTopic(author.getId());
        User voter1 = persistUser();
        User voter2 = persistUser();
        castUpvote(moreUpvoted.getId(), voter1.getId());
        castUpvote(moreUpvoted.getId(), voter2.getId());
        castUpvote(lessUpvoted.getId(), voter1.getId());
        User viewer = persistUser();

        String body = homeBody(viewer);

        assertThat(body.indexOf(moreUpvoted.getName())).isLessThan(body.indexOf(lessUpvoted.getName()));
    }

    @Test
    void homeHidesTheUpvoteControlAndCountAndDropsTheTiebreakWhenTheFeatureIsDisabled() {
        User author = persistUser();
        Topic topic = persistTopic(author.getId());
        User voter = persistUser();
        castUpvote(topic.getId(), voter.getId());
        setUpvotingEnabled(false);

        String body = homeBody(voter);

        assertThat(body).doesNotContain("Upvote");
        assertThat(body).doesNotContain("Withdraw upvote");
        // Feature 013 (FR-009): the announcement region exists only to narrate vote changes, so it
        // has no reason to be in the document when voting is switched off.
        assertThat(body).doesNotContain("vote-live-region");
        assertThat(body).doesNotContain("data-vote-button");
    }

    // --- Reference numbers (US2, FR-009, FR-010, FR-012) --------------------------------------------

    @Test
    void homeShowsTheReferenceNumberOfAnApprovedTopic() {
        User author = persistUser();
        Topic pending = persistTopicWithStatus(author.getId(), TopicApprovalStatus.PENDING);
        Topic approved = topicService.approve(pending.getId(), new AuditActor(author.getId(), true)).block();
        User viewer = persistUser();

        String body = homeBody(viewer);

        assertThat(body).contains("#" + approved.getReferenceNumber());
    }

    private void castUpvote(UUID topicId, UUID userId) {
        databaseClient
                .sql("INSERT INTO topic_upvotes (topic_id, user_id) VALUES (:tid, :uid)")
                .bind("tid", topicId)
                .bind("uid", userId)
                .then()
                .block();
    }

    private void setUpvotingEnabled(boolean enabled) {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setTopicUpvotingEnabled(enabled);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
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

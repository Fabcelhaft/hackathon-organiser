package net.fabcelhaft.hackathonorganiser.topics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
 * Integration tests for User Story 5's Topic Overview (T043; contracts/home-and-topic-
 * overview.md): every visible Topic, a Pending Topic shown only to its author/an Organiser, each
 * Compliance status rendered as text+icon (FR-025), and the nav item present for every
 * authenticated user unconditionally.
 */
@SpringBootTest
@Testcontainers
class TopicOverviewManagementIT {

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
     * The Topic Overview lists every Topic in the database with no cap — without this cleanup, a
     * negative assertion in one test (e.g. "this Compliance cell is blank") could spuriously fail
     * against text left behind by another test's unrelated Topic, mirroring the same fix already
     * applied to {@code HomeControllerIT}. Deleted in FK-dependency order (no
     * {@code ON DELETE CASCADE} in schema.sql).
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
                    settings.setMaxGroupMembers(5);
                    settings.setMinGroupMembers(null);
                    settings.setSkillDisplayMode(
                            net.fabcelhaft.hackathonorganiser.organisersettings.SkillDisplayMode.STILL_NEEDED_ONLY);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    @Test
    void overviewListsEveryVisibleTopicWithNameAuthorCountAndComplianceStatus() {
        User author = persistUser(false);
        Participant authorParticipant = persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        groupService.create(topic.getId(), List.of(authorParticipant.getId())).block();
        User viewer = persistUser(false);

        String body = overviewBody(viewer);

        assertThat(body).contains(topic.getName());
        assertThat(body).contains(author.getDisplayName());
        assertThat(body).containsAnyOf("Compliant", "Not Compliant");
    }

    @Test
    void aTopicWithNoGroupShowsABlankComplianceCellRatherThanAnyStatusText() {
        // FR-014a (superseding the old "No Group Yet" label): the row still appears, but its
        // Compliance cell carries none of the three determined-status labels.
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        User viewer = persistUser(false);

        String body = overviewBody(viewer);

        assertThat(body).contains(topic.getName());
        assertThat(body).doesNotContain("No Group Yet");
        assertThat(body).doesNotContain("Compliant");
    }

    @Test
    void aPendingTopicIsShownOnlyToItsAuthorOrAnOrganiser() {
        User author = persistUser(false);
        Topic pending = persistTopic(author.getId(), TopicApprovalStatus.PENDING);
        User otherViewer = persistUser(false);
        User organiser = persistUser(true);

        assertThat(overviewBody(author)).contains(pending.getName());
        assertThat(overviewBody(organiser)).contains(pending.getName());
        assertThat(overviewBody(otherViewer)).doesNotContain(pending.getName());
    }

    @Test
    void theNavItemIsPresentForEveryAuthenticatedUserUnconditionally() {
        User standardUser = persistUser(false);
        User organiser = persistUser(true);

        assertThat(homeBody(standardUser)).contains("/topics/overview");
        assertThat(homeBody(organiser)).contains("/topics/overview");
    }

    // --- Join action, View Details link, own-Topic pinning (Stories 9, 10; FR-006a, FR-006b, FR-034) --

    @Test
    void anEligibleViewerCanUseTheSameJoinActionOnTheOverviewAsOnTheHomePage() {
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        User viewer = persistUser(false);
        persistParticipant(viewer.getId(), ParticipantStatus.ACTIVE);

        String body = overviewBody(viewer);

        assertThat(body).contains("/topics/" + topic.getId() + "/join");
    }

    @Test
    void everyOverviewRowOffersAViewDetailsLink() {
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        User viewer = persistUser(false);

        String body = overviewBody(viewer);

        assertThat(body).contains("/topics/" + topic.getId());
    }

    @Test
    void theViewersOwnTopicsArePinnedAboveEveryOtherRowWithNothingHidden() {
        User viewer = persistUser(false);
        Topic own = persistTopic(viewer.getId(), TopicApprovalStatus.APPROVED);
        User otherAuthor = persistUser(false);
        Topic other = persistTopic(otherAuthor.getId(), TopicApprovalStatus.APPROVED);

        String body = overviewBody(viewer);

        assertThat(body).contains(own.getName());
        assertThat(body).contains(other.getName());
        assertThat(body.indexOf(own.getName())).isLessThan(body.indexOf(other.getName()));
    }

    // --- Skill Display Mode changes the Needed Skills column (Story 8, FR-017, FR-018) ------------

    @Test
    void skillDisplayModeChangesTheNeededSkillsColumnOnTheVeryNextView() {
        User author = persistUser(false);
        Participant authorParticipant = persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        Skill covered = persistSkill("Overview Covered " + UUID.randomUUID());
        Skill stillNeeded = persistSkill("Overview StillNeeded " + UUID.randomUUID());
        participantService.replaceSkills(authorParticipant.getId(), List.of(covered.getId())).block();
        Topic topic = topicService
                .propose(author.getId(), "Overview Mode Topic " + UUID.randomUUID(), "Desc",
                        List.of(covered.getId(), stillNeeded.getId()))
                .block();
        groupService.create(topic.getId(), List.of(authorParticipant.getId())).block();
        User viewer = persistUser(false);

        setSkillDisplayMode("STILL_NEEDED_ONLY");
        String stillNeededOnlyBody = overviewBody(viewer);
        assertThat(stillNeededOnlyBody).contains(stillNeeded.getName());
        assertThat(stillNeededOnlyBody).doesNotContain(covered.getName());

        setSkillDisplayMode("ALL_ASSOCIATED");
        String allAssociatedBody = overviewBody(viewer);
        assertThat(allAssociatedBody).contains(stillNeeded.getName());
        assertThat(allAssociatedBody).contains(covered.getName());
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

    private String overviewBody(User user) {
        return webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/topics/overview")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

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

    private Participant persistParticipant(UUID userId, ParticipantStatus status) {
        Participant participant = new Participant();
        participant.setUserId(userId);
        participant.setStatus(status);
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

    private Topic persistTopic(UUID creatorUserId, TopicApprovalStatus status) {
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

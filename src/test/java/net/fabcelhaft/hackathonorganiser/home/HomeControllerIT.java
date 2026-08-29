package net.fabcelhaft.hackathonorganiser.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsRepository;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantRepository;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
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

    @BeforeEach
    void setUpWebTestClient() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient()
                .build();
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
        groupService.create(topic.getId(), List.of(participant.getId())).block();

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
                .create(topic.getId(), List.of(participant.getId()))
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
        groupService.create(topic.getId(), List.of(participant.getId())).block();

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

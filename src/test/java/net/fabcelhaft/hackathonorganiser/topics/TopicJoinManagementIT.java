package net.fabcelhaft.hackathonorganiser.topics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabcelhaft.hackathonorganiser.audit.AuditActor;
import net.fabcelhaft.hackathonorganiser.group.GroupRepository;
import net.fabcelhaft.hackathonorganiser.group.GroupStatus;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsRepository;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantRepository;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.topic.Topic;
import net.fabcelhaft.hackathonorganiser.topic.TopicApprovalStatus;
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
 * Integration tests for User Story 3's self-service Join action (T033;
 * contracts/join-action.md): creates a Group on first join and grows it on later joins, rejects
 * once at Maximum, rejects a requester already in a different Group or ineligible, 404s for an
 * unknown/Pending Topic, allows a Topic's own author to join like anyone else, and — the one test
 * that needs a real database rather than a mock — resolves two concurrent last-slot join attempts
 * to exactly one success (Edge Cases, research.md §2), mirroring 004's concurrent-registration
 * race test.
 */
@SpringBootTest
@Testcontainers
class TopicJoinManagementIT {

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
    GroupRepository groupRepository;

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
                    settings.setTopicJoiningEnabled(true);
                    settings.setMaxGroupMembers(5);
                    settings.setMinGroupMembers(null);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    @Test
    void joinSucceedsImmediatelyWithASuccessFlashAndCreatesAGroupOnFirstJoin() {
        User author = persistUser();
        Topic topic = persistTopic(author.getId());
        User joiner = persistUser();
        persistParticipant(joiner.getId(), ParticipantStatus.ACTIVE);

        webTestClient
                .mutateWith(loginAs(joiner))
                .post()
                .uri("/topics/{id}/join", topic.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER)
                .expectHeader()
                .value("Location", location -> assertThat(location).contains("flash="));

        var group = groupRepository.findByTopicIdAndStatus(topic.getId(), GroupStatus.ACTIVE)
                .block();
        assertThat(group).isNotNull();
    }

    @Test
    void secondJoinGrowsTheExistingGroup() {
        User author = persistUser();
        Topic topic = persistTopic(author.getId());
        User firstJoiner = persistUser();
        persistParticipant(firstJoiner.getId(), ParticipantStatus.ACTIVE);
        User secondJoiner = persistUser();
        persistParticipant(secondJoiner.getId(), ParticipantStatus.ACTIVE);

        webTestClient.mutateWith(loginAs(firstJoiner)).post().uri("/topics/{id}/join", topic.getId()).exchange();
        webTestClient
                .mutateWith(loginAs(secondJoiner))
                .post()
                .uri("/topics/{id}/join", topic.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        var group = groupRepository
                .findByTopicIdAndStatus(topic.getId(), GroupStatus.ACTIVE)
                .block();
        assertThat(groupService.activeMemberCount(group.getId()).block()).isEqualTo(2);
    }

    @Test
    void joinIsRejectedOnceAtMaximum() {
        setMaxGroupMembers(1);
        User author = persistUser();
        Topic topic = persistTopic(author.getId());
        User firstJoiner = persistUser();
        Participant firstParticipant = persistParticipant(firstJoiner.getId(), ParticipantStatus.ACTIVE);
        groupService
                .create(topic.getId(), List.of(firstParticipant.getId()), new AuditActor(firstJoiner.getId(), false))
                .block();
        User secondJoiner = persistUser();
        persistParticipant(secondJoiner.getId(), ParticipantStatus.ACTIVE);

        webTestClient
                .mutateWith(loginAs(secondJoiner))
                .post()
                .uri("/topics/{id}/join", topic.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER)
                .expectHeader()
                .value("Location", location -> assertThat(location).contains("full"));

        var group = groupRepository
                .findByTopicIdAndStatus(topic.getId(), GroupStatus.ACTIVE)
                .block();
        assertThat(groupService.activeMemberCount(group.getId()).block()).isEqualTo(1);
    }

    @Test
    void joinIsRejectedForARequesterAlreadyInADifferentActiveGroup() {
        User author = persistUser();
        Topic topicA = persistTopic(author.getId());
        Topic topicB = persistTopic(author.getId());
        User joiner = persistUser();
        Participant participant = persistParticipant(joiner.getId(), ParticipantStatus.ACTIVE);
        groupService
                .create(topicA.getId(), List.of(participant.getId()), new AuditActor(joiner.getId(), false))
                .block();

        webTestClient
                .mutateWith(loginAs(joiner))
                .post()
                .uri("/topics/{id}/join", topicB.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER)
                .expectHeader()
                .value("Location", location -> assertThat(location).contains("already"));
    }

    @Test
    void joinIsRejectedForANonActiveOrUnknownRequester() {
        User author = persistUser();
        Topic topic = persistTopic(author.getId());
        User revokedUser = persistUser();
        persistParticipant(revokedUser.getId(), ParticipantStatus.REVOKED);
        User noRecordUser = persistUser();

        webTestClient
                .mutateWith(loginAs(revokedUser))
                .post()
                .uri("/topics/{id}/join", topic.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
        webTestClient
                .mutateWith(loginAs(noRecordUser))
                .post()
                .uri("/topics/{id}/join", topic.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(groupRepository
                        .findByTopicIdAndStatus(topic.getId(), GroupStatus.ACTIVE)
                        .blockOptional())
                .isEmpty();
    }

    @Test
    void joinReturns404ForAnUnknownOrPendingTopic() {
        User joiner = persistUser();
        persistParticipant(joiner.getId(), ParticipantStatus.ACTIVE);
        User author = persistUser();
        Topic pending = persistTopicWithStatus(author.getId(), TopicApprovalStatus.PENDING);

        webTestClient
                .mutateWith(loginAs(joiner))
                .post()
                .uri("/topics/{id}/join", UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isNotFound();
        webTestClient
                .mutateWith(loginAs(joiner))
                .post()
                .uri("/topics/{id}/join", pending.getId())
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void aTopicsOwnAuthorCanJoinItLikeAnyOtherEligibleParticipant() {
        User author = persistUser();
        persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        Topic topic = persistTopic(author.getId());

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics/{id}/join", topic.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(groupRepository
                        .findByTopicIdAndStatus(topic.getId(), GroupStatus.ACTIVE)
                        .block())
                .isNotNull();
    }

    @Test
    void twoConcurrentJoinsForTheLastOpenSlotResultInExactlyOneSuccess() throws Exception {
        setMaxGroupMembers(1);
        User author = persistUser();
        Topic topic = persistTopic(author.getId());
        User firstJoiner = persistUser();
        persistParticipant(firstJoiner.getId(), ParticipantStatus.ACTIVE);
        User secondJoiner = persistUser();
        persistParticipant(secondJoiner.getId(), ParticipantStatus.ACTIVE);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger fullCount = new AtomicInteger();

        List<User> joiners = List.of(firstJoiner, secondJoiner);
        List<Future<Void>> futures = new ArrayList<>();
        for (User user : joiners) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                go.await();
                String location = webTestClient
                        .mutateWith(loginAs(user))
                        .post()
                        .uri("/topics/{id}/join", topic.getId())
                        .exchange()
                        .expectStatus()
                        .isEqualTo(HttpStatus.SEE_OTHER)
                        .returnResult(String.class)
                        .getResponseHeaders()
                        .getLocation()
                        .toString();
                if (location.contains("full")) {
                    fullCount.incrementAndGet();
                } else {
                    successCount.incrementAndGet();
                }
                return null;
            }));
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(fullCount.get()).isEqualTo(1);
        var group = groupRepository
                .findByTopicIdAndStatus(topic.getId(), GroupStatus.ACTIVE)
                .block();
        assertThat(groupService.activeMemberCount(group.getId()).block()).isEqualTo(1);
    }

    // --- Topic-joining-enabled toggle enforcement (Story 4, FR-020a-d) ----------------------------

    @Test
    void noJoinActionRendersOnTheHomePageWhenTopicJoiningIsDisabled() {
        setTopicJoiningEnabled(false);
        User author = persistUser();
        Topic topic = persistTopic(author.getId());
        User viewer = persistUser();
        persistParticipant(viewer.getId(), ParticipantStatus.ACTIVE);

        String body = webTestClient
                .mutateWith(loginAs(viewer))
                .get()
                .uri("/")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).doesNotContain("/topics/" + topic.getId() + "/join");
    }

    @Test
    void aDirectJoinRequestIsRejectedServerSideWhenTopicJoiningIsDisabledEvenIfThePageRenderedTheButtonEarlier() {
        User author = persistUser();
        Topic topic = persistTopic(author.getId());
        User viewer = persistUser();
        persistParticipant(viewer.getId(), ParticipantStatus.ACTIVE);
        setTopicJoiningEnabled(false);

        webTestClient
                .mutateWith(loginAs(viewer))
                .post()
                .uri("/topics/{id}/join", topic.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER)
                .expectHeader()
                .value("Location", location -> assertThat(location).contains("disabled"));

        assertThat(groupRepository.findByTopicIdAndStatus(topic.getId(), GroupStatus.ACTIVE).blockOptional())
                .isEmpty();
    }

    @Test
    void reenablingTopicJoiningRestoresTheJoinActionImmediately() {
        setTopicJoiningEnabled(false);
        User author = persistUser();
        Topic topic = persistTopic(author.getId());
        User viewer = persistUser();
        persistParticipant(viewer.getId(), ParticipantStatus.ACTIVE);
        setTopicJoiningEnabled(true);

        String body = webTestClient
                .mutateWith(loginAs(viewer))
                .get()
                .uri("/")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("/topics/" + topic.getId() + "/join");
    }

    @Test
    void aRevokedOrNotParticipatedViewerNeverSeesTheJoinActionRegardlessOfTheToggle() {
        User author = persistUser();
        Topic topic = persistTopic(author.getId());
        User revokedUser = persistUser();
        persistParticipant(revokedUser.getId(), ParticipantStatus.REVOKED);

        String body = webTestClient
                .mutateWith(loginAs(revokedUser))
                .get()
                .uri("/")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).doesNotContain("/topics/" + topic.getId() + "/join");
    }

    // --- Test helpers ------------------------------------------------------------------------------

    private void setTopicJoiningEnabled(boolean enabled) {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setTopicJoiningEnabled(enabled);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
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
        return persistTopicWithStatus(creatorUserId, TopicApprovalStatus.APPROVED);
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

package net.fabcelhaft.hackathonorganiser.audit;

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
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupRepository;
import net.fabcelhaft.hackathonorganiser.group.GroupStatus;
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
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration tests for User Story 3 (T035, FR-004, FR-004a): self-service Topic join/leave and
 * the organiser's direct Group add/remove-member routes produce the identical two-linked-entry
 * {@code JOINED}/{@code LEFT} shape, sharing one {@code action_id} — differing only in the
 * recorded actor and capacity — and the research.md §5 concurrency fix (a Participant-scoped
 * advisory lock in {@code addMember}/{@code removeMember}) closes the cross-topic race so a lost
 * race is rejected cleanly, never with a raw {@code 500}.
 */
@SpringBootTest
@Testcontainers
class AuditMembershipPairingIT {

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
    OrganiserSettingsRepository organiserSettingsRepository;

    @Autowired
    AuditEntryRepository auditEntryRepository;

    @BeforeEach
    void setUpWebTestClient() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    @Test
    void selfServiceJoinThenLeaveProduceIdenticallyShapedPairsAsTheOrganisersDirectAddAndRemove() {
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId());
        User joinerUser = persistUser(false);
        Participant joiner = persistParticipant(joinerUser.getId());

        // (a) Self-service join.
        webTestClient
                .mutateWith(loginAs(joinerUser))
                .post()
                .uri("/topics/{id}/join", topic.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        List<AuditEntry> joinedEntries = entriesFor(AuditEventType.JOINED, joiner.getId());
        assertPairedShape(joinedEntries, topic.getId(), joiner.getId(), false);

        // (b) Self-service leave.
        webTestClient
                .mutateWith(loginAs(joinerUser))
                .post()
                .uri("/topics/{id}/leave", topic.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        List<AuditEntry> leftEntries = entriesFor(AuditEventType.LEFT, joiner.getId());
        assertPairedShape(leftEntries, topic.getId(), joiner.getId(), false);

        // (c) Organiser directly adds a different Participant — the leave above disbanded the
        // only Group this Topic had, so the organiser first forms a fresh one to add into.
        User organiser = persistUser(true);
        User directUser = persistUser(false);
        Participant directParticipant = persistParticipant(directUser.getId());

        webTestClient
                .mutateWith(loginAs(organiser))
                .post()
                .uri("/organiser/groups")
                .body(BodyInserters.fromFormData("topic_id", topic.getId().toString()))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        webTestClient
                .mutateWith(loginAs(organiser))
                .post()
                .uri("/organiser/groups/{id}/members", activeGroupId(topic.getId()))
                .body(BodyInserters.fromFormData("participant_id", directParticipant.getId().toString()))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        List<AuditEntry> organiserJoinedEntries = entriesFor(AuditEventType.JOINED, directParticipant.getId());
        assertPairedShape(organiserJoinedEntries, topic.getId(), directParticipant.getId(), true);

        // (d) Organiser directly removes that same Participant.
        webTestClient
                .mutateWith(loginAs(organiser))
                .post()
                .uri(
                        "/organiser/groups/{id}/members/{pid}/remove",
                        activeGroupId(topic.getId()),
                        directParticipant.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        List<AuditEntry> organiserLeftEntries = entriesFor(AuditEventType.LEFT, directParticipant.getId());
        assertPairedShape(organiserLeftEntries, topic.getId(), directParticipant.getId(), true);
    }

    @Test
    void twoConcurrentJoinsForTheSameParticipantToDifferentTopicsResultInExactlyOneSuccess() throws Exception {
        setMaxGroupMembers(5);
        User authorA = persistUser(false);
        User authorB = persistUser(false);
        Topic topicA = persistTopic(authorA.getId());
        Topic topicB = persistTopic(authorB.getId());
        User joinerUser = persistUser(false);
        persistParticipant(joinerUser.getId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();
        AtomicInteger serverErrorCount = new AtomicInteger();

        List<Topic> topics = List.of(topicA, topicB);
        List<Future<Void>> futures = new ArrayList<>();
        for (Topic topic : topics) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                go.await();
                // Both success and a friendly rejection redirect with 303 (TopicJoinController
                // translates both TopicJoinConflictException and GroupConflictException into the
                // same redirect-with-flash shape) — the two are distinguished only by the flash
                // message text in the Location header, exactly like the existing same-topic
                // concurrency test (TopicJoinManagementIT) already does.
                var result = webTestClient
                        .mutateWith(loginAs(joinerUser))
                        .post()
                        .uri("/topics/{id}/join", topic.getId())
                        .exchange()
                        .returnResult(String.class);
                int status = result.getStatus().value();
                if (status >= 500) {
                    serverErrorCount.incrementAndGet();
                } else if (status != HttpStatus.SEE_OTHER.value()) {
                    serverErrorCount.incrementAndGet();
                } else {
                    String location = result.getResponseHeaders().getLocation().toString();
                    if (location.contains("joined")) {
                        successCount.incrementAndGet();
                    } else {
                        rejectedCount.incrementAndGet();
                    }
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

        assertThat(serverErrorCount.get()).isZero();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(rejectedCount.get()).isEqualTo(1);
    }

    // --- Test helpers ------------------------------------------------------------------------------

    private List<AuditEntry> entriesFor(AuditEventType type, UUID participantId) {
        return auditEntryRepository
                .findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(AuditSubjectType.PARTICIPANT, participantId)
                .filter(entry -> entry.getEventType() == type)
                .collectList()
                .block();
    }

    private void assertPairedShape(
            List<AuditEntry> participantSideEntries, UUID topicId, UUID participantId, boolean organiser) {
        assertThat(participantSideEntries).hasSize(1);
        AuditEntry participantEntry = participantSideEntries.get(0);
        assertThat(participantEntry.isOrganiser()).isEqualTo(organiser);
        assertThat(participantEntry.getActionId()).isNotNull();

        List<AuditEntry> topicSideEntries = auditEntryRepository
                .findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(AuditSubjectType.TOPIC, topicId)
                .filter(entry -> entry.getActionId() != null
                        && entry.getActionId().equals(participantEntry.getActionId()))
                .collectList()
                .block();
        assertThat(topicSideEntries).hasSize(1);
        AuditEntry topicEntry = topicSideEntries.get(0);
        assertThat(topicEntry.getEventType()).isEqualTo(participantEntry.getEventType());
        assertThat(topicEntry.isOrganiser()).isEqualTo(organiser);
        assertThat(topicEntry.getSubjectId()).isEqualTo(topicId);
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

    private UUID activeGroupId(UUID topicId) {
        Group group = groupRepository.findByTopicIdAndStatus(topicId, GroupStatus.ACTIVE).block();
        return group.getId();
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

    private Topic persistTopic(UUID creatorUserId) {
        Topic topic = new Topic();
        topic.setName("Topic " + UUID.randomUUID());
        topic.setDescription("Description");
        topic.setCreatedByUserId(creatorUserId);
        topic.setApprovalStatus(TopicApprovalStatus.APPROVED);
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

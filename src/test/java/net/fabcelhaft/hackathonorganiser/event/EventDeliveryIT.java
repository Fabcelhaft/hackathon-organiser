package net.fabcelhaft.hackathonorganiser.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.fabcelhaft.hackathonorganiser.audit.AuditActor;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinition;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldService;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldType;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantRepository;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantService;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.topic.Topic;
import net.fabcelhaft.hackathonorganiser.topic.TopicApprovalStatus;
import net.fabcelhaft.hackathonorganiser.topic.TopicRepository;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
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
 * End-to-end integration test proving spec.md's User Story 3: a live domain occurrence (a
 * Participant joining an open Topic with no existing Group) is delivered, asynchronously, to a
 * real enabled HTTP POST Destination — and that the triggering join itself completes promptly,
 * never blocked on that delivery (FR-020a-1, SC-008). Uses a throwaway JDK {@link HttpServer}
 * (research.md §8) as the capture endpoint, exactly like quickstart.md step 3 describes doing
 * manually.
 */
@SpringBootTest
@Testcontainers
class EventDeliveryIT {

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
    EventDestinationService eventDestinationService;

    @Autowired
    CustomFieldService customFieldService;

    @Autowired
    ParticipantService participantService;

    private HttpServer captureServer;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private CountDownLatch receivedLatch;

    @BeforeEach
    void setUpWebTestClient() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    @BeforeEach
    void startCaptureServer() throws IOException {
        receivedLatch = new CountDownLatch(2);
        captureServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        captureServer.createContext("/", exchange -> {
            try {
                receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                receivedLatch.countDown();
                exchange.sendResponseHeaders(200, -1);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            } finally {
                exchange.close();
            }
        });
        captureServer.start();
    }

    @AfterEach
    void stopCaptureServer() {
        if (captureServer != null) {
            captureServer.stop(0);
        }
    }

    @Test
    void joiningATopicDeliversBothEventsToTheSubscribedDestinationWithoutDelayingTheJoinItself() {
        User author = persistUser();
        Topic topic = persistTopic(author.getId());
        User joiner = persistUser();
        Participant participant = persistParticipant(joiner.getId());
        CustomFieldDefinition tshirtSize = customFieldService
                .create("T-shirt size", CustomFieldType.FREE_TEXT, false, null, false, false)
                .block();
        participantService
                .setCustomFieldValue(
                        participant.getId(), tshirtSize.getId(), "M", List.of(), new AuditActor(joiner.getId(), false))
                .block();

        eventDestinationService
                .create(
                        "Capture " + UUID.randomUUID(),
                        EventDestinationType.HTTP_POST,
                        null,
                        null,
                        "http://localhost:" + captureServer.getAddress().getPort(),
                        null,
                        List.of(EventType.PARTICIPANT_JOINED_TOPIC, EventType.GROUP_FORMED))
                .flatMap(destination -> eventDestinationService.enable(destination.getId()))
                .block();

        long start = System.nanoTime();
        webTestClient
                .mutateWith(loginAs(joiner))
                .post()
                .uri("/topics/{id}/join", topic.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // The join's own response returned promptly — event delivery never gated it (FR-020a-1).
        assertThat(elapsedMillis).isLessThan(5_000);

        boolean bothReceived = awaitUninterruptibly(receivedLatch, 15, TimeUnit.SECONDS);
        assertThat(bothReceived)
                .as("expected both PARTICIPANT_JOINED_TOPIC and GROUP_FORMED to arrive: %s", receivedBodies)
                .isTrue();
        assertThat(receivedBodies).hasSize(2);
        assertThat(receivedBodies.stream().filter(b -> b.contains("\"PARTICIPANT_JOINED_TOPIC\"")).count())
                .isEqualTo(1);
        assertThat(receivedBodies.stream().filter(b -> b.contains("\"GROUP_FORMED\"")).count())
                .isEqualTo(1);

        // FR-010c/FR-010d: the PARTICIPANT_JOINED_TOPIC message also carries the joining
        // Participant's User and their stored Custom Field answer.
        String joinedTopicBody = receivedBodies.stream()
                .filter(b -> b.contains("\"PARTICIPANT_JOINED_TOPIC\""))
                .findFirst()
                .orElseThrow();
        assertThat(joinedTopicBody)
                .contains("\"user\"")
                .contains(joiner.getEmail())
                .contains("\"T-shirt size\"")
                .contains("\"freeTextValue\":\"M\"");
    }

    private static boolean awaitUninterruptibly(CountDownLatch latch, long timeout, TimeUnit unit) {
        try {
            return latch.await(timeout, unit);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
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
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        DefaultOidcUser delegate = new DefaultOidcUser(authorities, idToken);
        HackathonOidcUser principal = new HackathonOidcUser(user, delegate);
        return mockOidcLogin().oidcUser(principal);
    }
}

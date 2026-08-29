package net.fabcelhaft.hackathonorganiser.topics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
 * Integration tests for User Story 3's Topic browse/propose/edit self-service (T021;
 * contracts/topics-self-service-and-approval.md): the homepage topic list shows name, description,
 * and author display-name+OIDC-subject (FR-009); propose creates a Topic authored by the current
 * Participant, starting Pending/Approved per the current setting (FR-013); a Standard user is
 * denied propose; the author can edit their own Topic, a non-author cannot (FR-011); the author's
 * own Pending Topic sorts to the top labeled "Pending approval" (FR-009a, FR-012b); a Pending
 * Topic is invisible to any other non-Organiser viewer (FR-012a); a blank field re-renders the
 * propose form with a field-associated error (FR-037).
 */
@SpringBootTest
@Testcontainers
class TopicSelfServiceManagementIT {

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

    @Test
    void homepageTopicListShowsNameDescriptionAndAuthorDisplayNameWithOidcSubject() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        Topic topic = persistTopic(author.getId(), "Robotics", "Build a robot", TopicApprovalStatus.APPROVED);

        String body = homeBody(author);

        assertThat(body).contains(topic.getName());
        assertThat(body).contains(topic.getDescription());
        assertThat(body).contains(author.getDisplayName());
        assertThat(body).contains(author.getOidcSubject());
    }

    @Test
    void proposeCreatesATopicAuthoredByTheCurrentParticipantStartingApprovedWhenApprovalNotRequired() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        String name = "Proposed Topic " + UUID.randomUUID();

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics")
                .body(BodyInserters.fromFormData("name", name).with("description", "A description"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        Topic saved = findByName(name);
        assertThat(saved.getCreatedByUserId()).isEqualTo(author.getId());
        assertThat(saved.getApprovalStatus()).isEqualTo(TopicApprovalStatus.APPROVED);
    }

    @Test
    void proposeStartsPendingWhenTopicApprovalIsRequired() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        enableTopicApprovalRequired();
        String name = "Pending Proposed Topic " + UUID.randomUUID();

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics")
                .body(BodyInserters.fromFormData("name", name).with("description", "A description"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(findByName(name).getApprovalStatus()).isEqualTo(TopicApprovalStatus.PENDING);
    }

    @Test
    void standardUserWithNoParticipantRecordIsDeniedProposeRoutes() {
        User standardUser = persistUser(false);

        webTestClient
                .mutateWith(loginAs(standardUser))
                .get()
                .uri("/topics/new")
                .exchange()
                .expectStatus()
                .isForbidden();

        webTestClient
                .mutateWith(loginAs(standardUser))
                .post()
                .uri("/topics")
                .body(BodyInserters.fromFormData("name", "Nope").with("description", "Nope"))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void authorCanEditTheirOwnTopicButANonAuthorCannot() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        User otherUser = persistUser(false);
        persistParticipant(otherUser.getId());
        Topic topic = persistTopic(author.getId(), "Old Name", "Old Desc", TopicApprovalStatus.APPROVED);

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics/{id}", topic.getId())
                .body(BodyInserters.fromFormData("name", "New Name").with("description", "New Desc"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        Topic updated = topicRepository.findById(topic.getId()).block();
        assertThat(updated.getName()).isEqualTo("New Name");
        assertThat(updated.getDescription()).isEqualTo("New Desc");

        webTestClient
                .mutateWith(loginAs(otherUser))
                .get()
                .uri("/topics/{id}/edit", topic.getId())
                .exchange()
                .expectStatus()
                .isForbidden();

        webTestClient
                .mutateWith(loginAs(otherUser))
                .post()
                .uri("/topics/{id}", topic.getId())
                .body(BodyInserters.fromFormData("name", "Hijacked").with("description", "Hijacked"))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void authorsOwnPendingTopicSortsToTheTopLabeledPendingApproval() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        persistTopic(author.getId(), "Already Approved", "Desc", TopicApprovalStatus.APPROVED);
        Topic pending = persistTopic(author.getId(), "My Pending Topic", "Desc", TopicApprovalStatus.PENDING);

        String body = homeBody(author);

        assertThat(body).contains("Pending approval");
        assertThat(body).contains(pending.getName());
        assertThat(body.indexOf(pending.getName())).isLessThan(body.indexOf("Already Approved"));
    }

    @Test
    void pendingTopicIsInvisibleToAnyOtherNonOrganiserViewer() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        User otherViewer = persistUser(false);
        persistParticipant(otherViewer.getId());
        Topic pending =
                persistTopic(author.getId(), "Hidden Pending Topic " + UUID.randomUUID(), "Desc", TopicApprovalStatus.PENDING);

        String body = homeBody(otherViewer);
        assertThat(body).doesNotContain(pending.getName());

        webTestClient
                .mutateWith(loginAs(otherViewer))
                .get()
                .uri("/topics/{id}/edit", pending.getId())
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void proposeWithBlankNameOrDescriptionReRendersTheFormWithAFieldAssociatedError() {
        User author = persistUser(false);
        persistParticipant(author.getId());

        String body = webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics")
                .body(BodyInserters.fromFormData("name", "").with("description", "Some description"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("required");
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

    private Topic findByName(String name) {
        return topicRepository.findAll().filter(t -> t.getName().equals(name)).blockFirst();
    }

    private void enableTopicApprovalRequired() {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setTopicApprovalRequired(true);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
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

    private Topic persistTopic(UUID creatorUserId, String name, String description, TopicApprovalStatus status) {
        Topic topic = new Topic();
        topic.setName(name);
        topic.setDescription(description);
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

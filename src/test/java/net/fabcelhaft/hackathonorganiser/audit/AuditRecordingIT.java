package net.fabcelhaft.hackathonorganiser.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupRepository;
import net.fabcelhaft.hackathonorganiser.group.GroupStatus;
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
 * Integration tests for User Story 1 (T014, FR-001-FR-004): every Topic, Participant, and
 * Group-disband mutation writes a correctly-attributed {@link AuditEntry} — capturing actor,
 * capacity, event type, and subject — regardless of whether a Participant or an Organiser
 * performed it. Independent of any viewing UI (User Story 2's routes are exercised elsewhere).
 */
@SpringBootTest
@Testcontainers
class AuditRecordingIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @Autowired
    ApplicationContext applicationContext;

    WebTestClient webTestClient;

    @Autowired
    UserRepository userRepository;

    @Autowired
    TopicRepository topicRepository;

    @Autowired
    ParticipantRepository participantRepository;

    @Autowired
    GroupRepository groupRepository;

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
    void editingAnOwnedTopicAsItsAuthorRecordsACorrectlyAttributedEditedEntry() {
        User author = persistUser("Author " + UUID.randomUUID(), false);
        Topic topic = persistTopic(author.getId(), "Old Name", "Old Description");

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics/{id}", topic.getId())
                .body(BodyInserters.fromFormData("name", "New Name").with("description", "New Description"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        List<AuditEntry> entries = auditEntryRepository
                .findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(AuditSubjectType.TOPIC, topic.getId())
                .collectList()
                .block();
        assertThat(entries).hasSize(1);
        AuditEntry entry = entries.get(0);
        assertThat(entry.getEventType()).isEqualTo(AuditEventType.EDITED);
        assertThat(entry.getActorUserId()).isEqualTo(author.getId());
        assertThat(entry.isOrganiser()).isFalse();
        assertThat(entry.getSubjectId()).isEqualTo(topic.getId());
    }

    @Test
    void changingAParticipantsStatusAsAnOrganiserRecordsACorrectlyAttributedStatusChangedEntry() {
        User organiser = persistUser("Organiser " + UUID.randomUUID(), true);
        User participantUser = persistUser("Participant " + UUID.randomUUID(), false);
        Participant participant = persistParticipant(participantUser.getId());

        webTestClient
                .mutateWith(loginAs(organiser))
                .post()
                .uri("/organiser/participants/{id}/status", participant.getId())
                .body(BodyInserters.fromFormData("status", "REVOKED"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        List<AuditEntry> entries = auditEntryRepository
                .findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                        AuditSubjectType.PARTICIPANT, participant.getId())
                .collectList()
                .block();
        assertThat(entries).hasSize(1);
        AuditEntry entry = entries.get(0);
        assertThat(entry.getEventType()).isEqualTo(AuditEventType.STATUS_CHANGED);
        assertThat(entry.getActorUserId()).isEqualTo(organiser.getId());
        assertThat(entry.isOrganiser()).isTrue();
        assertThat(entry.getSubjectId()).isEqualTo(participant.getId());
        assertThat(entry.getOldValue()).isEqualTo("ACTIVE");
        assertThat(entry.getNewValue()).isEqualTo("REVOKED");
    }

    @Test
    void disbandingAGroupAsAnOrganiserRecordsACorrectlyAttributedDisbandedEntryAgainstTheTopic() {
        User organiser = persistUser("Organiser " + UUID.randomUUID(), true);
        User author = persistUser("Author " + UUID.randomUUID(), false);
        Topic topic = persistTopic(author.getId(), "Disband Topic " + UUID.randomUUID(), "Desc");
        Group group = persistActiveGroup(topic.getId());

        webTestClient
                .mutateWith(loginAs(organiser))
                .post()
                .uri("/organiser/groups/{id}/disband", group.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        List<AuditEntry> entries = auditEntryRepository
                .findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(AuditSubjectType.TOPIC, topic.getId())
                .collectList()
                .block();
        assertThat(entries).hasSize(1);
        AuditEntry entry = entries.get(0);
        assertThat(entry.getEventType()).isEqualTo(AuditEventType.DISBANDED);
        assertThat(entry.getActorUserId()).isEqualTo(organiser.getId());
        assertThat(entry.isOrganiser()).isTrue();
        assertThat(entry.getSubjectId()).isEqualTo(topic.getId());
    }

    // --- Test helpers ------------------------------------------------------------------------------

    private User persistUser(String displayName, boolean organiser) {
        User user = new User();
        user.setOidcSubject("sub-" + UUID.randomUUID());
        user.setDisplayName(displayName);
        user.setEmail(displayName.toLowerCase().replace(' ', '.') + "@example.com");
        user.setOrganiser(organiser);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).block();
    }

    private Topic persistTopic(UUID creatorUserId, String name, String description) {
        Topic topic = new Topic();
        topic.setName(name);
        topic.setDescription(description);
        topic.setCreatedByUserId(creatorUserId);
        topic.setApprovalStatus(TopicApprovalStatus.APPROVED);
        Instant now = Instant.now();
        topic.setCreatedAt(now);
        topic.setUpdatedAt(now);
        return topicRepository.save(topic).block();
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

    private Group persistActiveGroup(UUID topicId) {
        Group group = new Group();
        group.setTopicId(topicId);
        group.setStatus(GroupStatus.ACTIVE);
        Instant now = Instant.now();
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        return groupRepository.save(group).block();
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

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
import net.fabcelhaft.hackathonorganiser.audit.AuditActor;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinition;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinitionRepository;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldType;
import net.fabcelhaft.hackathonorganiser.group.GroupRepository;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
import net.fabcelhaft.hackathonorganiser.group.GroupStatus;
import net.fabcelhaft.hackathonorganiser.organisersettings.DirectoryAudience;
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
 * Integration tests for Story 9's Topic Details view (T075; contracts/topic-details.md):
 * {@code GET /topics/{id}} shows the Topic's full information plus its currently joined
 * Participants, gated only by the same visibility {@code GET /topics/{id}/edit} and
 * {@code GET /topics/overview} already apply — never by the separate Participants-Directory
 * audience setting — and never mentions "Group" anywhere on the page (FR-036); plus Story 11's
 * self-service Leave action (T090/T091; contracts/topic-details.md): {@code POST
 * /topics/{id}/leave} removes the requester immediately, disbands the Group when they were its
 * last member, and — the one test that needs a real database rather than a mock — resolves two
 * concurrent last-member leaves to disbandment applied exactly once, mirroring the Join race test
 * (research.md §14).
 */
@SpringBootTest
@Testcontainers
class TopicDetailManagementIT {

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
    CustomFieldDefinitionRepository customFieldDefinitionRepository;

    @Autowired
    OrganiserSettingsRepository organiserSettingsRepository;

    @Autowired
    DatabaseClient databaseClient;

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
                    settings.setParticipantsDirectoryAudience(DirectoryAudience.ALL_AUTHENTICATED);
                    settings.setSkillVisibilityEnabled(false);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    @Test
    void showsNameDescriptionSkillsCountAndABlankComplianceCellWhenThereIsNoGroupYet() {
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        User viewer = persistUser(false);

        String body = detailBody(viewer, topic.getId());

        assertThat(body).contains(topic.getName());
        assertThat(body).contains(topic.getDescription());
        assertThat(body).containsIgnoringCase("no one has joined yet");
        assertThat(body).doesNotContain("Compliant"); // FR-014a: blank, not "No Group Yet"
    }

    @Test
    void theTopicInfoAndJoinedParticipantsSectionsRenderAsTablesNotDefinitionListsOrPlainLists() {
        User author = persistUser(false);
        Participant authorParticipant = persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        groupService.create(topic.getId(), List.of(authorParticipant.getId()), new AuditActor(author.getId(), true)).block();
        User viewer = persistUser(false);

        String body = detailBody(viewer, topic.getId());

        assertThat(body).doesNotContain("<dl");
        assertThat(body).contains("Topic Info");
        assertThat(body).contains("Joined Participants");
        // Two distinct tables: one row-per-key-value "Topic Info" table, one row-per-member
        // "Joined Participants" table (FR-030, FR-031) — the page's nav menu also uses <ul>, so
        // only <table>/<dl> presence (not <ul>) meaningfully distinguishes the new markup.
        assertThat(body.split("<table", -1).length - 1).isEqualTo(2);
    }

    @Test
    void aVisibleTopicWithAJoinedMemberListsThatMemberByDisplayName() {
        User author = persistUser(false);
        Participant authorParticipant = persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        groupService.create(topic.getId(), List.of(authorParticipant.getId()), new AuditActor(author.getId(), true)).block();
        User viewer = persistUser(false);

        String body = detailBody(viewer, topic.getId());

        assertThat(body).contains(author.getDisplayName());
        assertThat(body).containsAnyOf("Compliant", "Not Compliant");
    }

    @Test
    void aNonMemberNonOrganiserViewerSeesOnlyPublicFieldsAndSkillsOnlyWhenVisibilityIsEnabled() {
        CustomFieldDefinition publicField = persistDefinition("Public Field", true);
        CustomFieldDefinition privateField = persistDefinition("Private Field", false);
        User author = persistUser(false);
        Participant authorParticipant = persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        insertFreeTextValue(authorParticipant.getId(), publicField.getId(), "PublicValue");
        insertFreeTextValue(authorParticipant.getId(), privateField.getId(), "PrivateValue");
        String skillName = "DistinctiveSkill" + UUID.randomUUID().toString().replace("-", "");
        UUID skillId = persistSkill(skillName);
        assignSkill(authorParticipant.getId(), skillId);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        groupService.create(topic.getId(), List.of(authorParticipant.getId()), new AuditActor(author.getId(), true)).block();
        User viewer = persistUser(false);

        String hiddenSkillBody = detailBody(viewer, topic.getId());
        assertThat(hiddenSkillBody).contains("PublicValue");
        assertThat(hiddenSkillBody).doesNotContain("PrivateValue");
        assertThat(hiddenSkillBody).doesNotContain(skillName);

        setSkillVisibility(true);
        String shownSkillBody = detailBody(viewer, topic.getId());
        assertThat(shownSkillBody).contains(skillName);
    }

    @Test
    void theAuthorAndAnOrganiserSeeEveryFieldAndSkillRegardlessOfVisibilityFlags() {
        CustomFieldDefinition privateField = persistDefinition("Private Field", false);
        User author = persistUser(false);
        Participant authorParticipant = persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        insertFreeTextValue(authorParticipant.getId(), privateField.getId(), "SecretValue");
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        groupService.create(topic.getId(), List.of(authorParticipant.getId()), new AuditActor(author.getId(), true)).block();
        User organiser = persistUser(true);

        assertThat(detailBody(author, topic.getId())).contains("SecretValue");
        assertThat(detailBody(organiser, topic.getId())).contains("SecretValue");
    }

    @Test
    void onlyTheAuthorSeesAnEditLink() {
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        User otherViewer = persistUser(false);

        assertThat(detailBody(author, topic.getId())).contains("/topics/" + topic.getId() + "/edit");
        assertThat(detailBody(otherViewer, topic.getId())).doesNotContain("/topics/" + topic.getId() + "/edit");
    }

    @Test
    void returns404ForAnUnknownOrAnInvisiblePendingTopic() {
        User author = persistUser(false);
        Topic pending = persistTopic(author.getId(), TopicApprovalStatus.PENDING);
        User otherViewer = persistUser(false);

        webTestClient
                .mutateWith(loginAs(otherViewer))
                .get()
                .uri("/topics/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isNotFound();

        webTestClient
                .mutateWith(loginAs(otherViewer))
                .get()
                .uri("/topics/{id}", pending.getId())
                .exchange()
                .expectStatus()
                .isNotFound();

        // But the author and an Organiser can both still see it.
        webTestClient
                .mutateWith(loginAs(author))
                .get()
                .uri("/topics/{id}", pending.getId())
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void aViewerOutsideTheDirectoryAudienceCanStillSeeWhoHasJoined() {
        User author = persistUser(false);
        Participant authorParticipant = persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        groupService.create(topic.getId(), List.of(authorParticipant.getId()), new AuditActor(author.getId(), true)).block();
        User viewer = persistUser(false);
        setDirectoryAudience(DirectoryAudience.ORGANISERS_ONLY);

        webTestClient
                .mutateWith(loginAs(viewer))
                .get()
                .uri("/participants")
                .exchange()
                .expectStatus()
                .isForbidden();

        String body = detailBody(viewer, topic.getId());
        assertThat(body).contains(author.getDisplayName());
    }

    @Test
    void thePageNeverMentionsGroup() {
        User author = persistUser(false);
        Participant authorParticipant = persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        groupService.create(topic.getId(), List.of(authorParticipant.getId()), new AuditActor(author.getId(), true)).block();
        User viewer = persistUser(false);

        String body = detailBody(viewer, topic.getId());

        assertThat(body).doesNotContainIgnoringCase("group");
    }

    // --- Leave action (Story 11, FR-037-FR-037e) --------------------------------------------------

    @Test
    void aCurrentMemberSeesTheLeaveActionAndANonMemberDoesNot() {
        User author = persistUser(false);
        Participant authorParticipant = persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        groupService.create(topic.getId(), List.of(authorParticipant.getId()), new AuditActor(author.getId(), true)).block();
        User nonMemberViewer = persistUser(false);

        assertThat(detailBody(author, topic.getId())).contains("/topics/" + topic.getId() + "/leave");
        assertThat(detailBody(nonMemberViewer, topic.getId())).doesNotContain("/topics/" + topic.getId() + "/leave");
    }

    @Test
    void leaveSucceedsImmediatelyAndRedirectsBackToTheTopicDetailsPageWithASuccessFlash() {
        User author = persistUser(false);
        Participant authorParticipant = persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        User secondMember = persistUser(false);
        Participant secondParticipant = persistParticipant(secondMember.getId(), ParticipantStatus.ACTIVE);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        groupService
                .create(
                        topic.getId(),
                        List.of(authorParticipant.getId(), secondParticipant.getId()),
                        new AuditActor(author.getId(), true))
                .block();

        webTestClient
                .mutateWith(loginAs(secondMember))
                .post()
                .uri("/topics/{id}/leave", topic.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER)
                .expectHeader()
                .value("Location", location -> {
                    assertThat(location).contains("/topics/" + topic.getId());
                    assertThat(location).contains("flash=");
                    assertThat(location).doesNotContainIgnoringCase("group");
                });

        String body = detailBody(author, topic.getId());
        assertThat(body).doesNotContain(secondMember.getDisplayName());
        assertThat(body).contains(author.getDisplayName());
    }

    @Test
    void leavingTheLastMemberDisbandsTheGroupAndTheTopicBecomesJoinableAgain() {
        User author = persistUser(false);
        Participant authorParticipant = persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        groupService.create(topic.getId(), List.of(authorParticipant.getId()), new AuditActor(author.getId(), true)).block();

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics/{id}/leave", topic.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(groupRepository.findByTopicIdAndStatus(topic.getId(), GroupStatus.ACTIVE).blockOptional())
                .isEmpty();
        String body = detailBody(author, topic.getId());
        assertThat(body).containsIgnoringCase("no one has joined yet");
        assertThat(body).doesNotContain("Compliant"); // covers "Not Compliant" too (substring)

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics/{id}/join", topic.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(groupRepository.findByTopicIdAndStatus(topic.getId(), GroupStatus.ACTIVE).blockOptional())
                .isPresent();
    }

    @Test
    void leavingFreesTheParticipantToImmediatelyJoinADifferentTopic() {
        User author = persistUser(false);
        Participant authorParticipant = persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        Topic topicA = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        Topic topicB = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        groupService.create(topicA.getId(), List.of(authorParticipant.getId()), new AuditActor(author.getId(), true)).block();

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics/{id}/leave", topicA.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics/{id}/join", topicB.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(groupRepository.findByTopicIdAndStatus(topicB.getId(), GroupStatus.ACTIVE).blockOptional())
                .isPresent();
    }

    @Test
    void leaveIsRejectedForARequesterNotCurrentlyAMemberOfThisTopicsGroup() {
        User author = persistUser(false);
        Participant authorParticipant = persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        groupService.create(topic.getId(), List.of(authorParticipant.getId()), new AuditActor(author.getId(), true)).block();
        User outsider = persistUser(false);
        persistParticipant(outsider.getId(), ParticipantStatus.ACTIVE);
        User noRecordUser = persistUser(false);

        webTestClient
                .mutateWith(loginAs(outsider))
                .post()
                .uri("/topics/{id}/leave", topic.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
        webTestClient
                .mutateWith(loginAs(noRecordUser))
                .post()
                .uri("/topics/{id}/leave", topic.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        var group = groupRepository.findByTopicIdAndStatus(topic.getId(), GroupStatus.ACTIVE).block();
        assertThat(group).isNotNull();
        assertThat(groupService.activeMemberCount(group.getId()).block()).isEqualTo(1);
    }

    @Test
    void twoConcurrentLeavesWhereOneIsTheLastMemberDisbandTheGroupExactlyOnce() throws Exception {
        User firstMember = persistUser(false);
        Participant firstParticipant = persistParticipant(firstMember.getId(), ParticipantStatus.ACTIVE);
        User secondMember = persistUser(false);
        Participant secondParticipant = persistParticipant(secondMember.getId(), ParticipantStatus.ACTIVE);
        Topic topic = persistTopic(firstMember.getId(), TopicApprovalStatus.APPROVED);
        var group = groupService
                .create(
                        topic.getId(),
                        List.of(firstParticipant.getId(), secondParticipant.getId()),
                        new AuditActor(firstMember.getId(), true))
                .block();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        List<User> leavers = List.of(firstMember, secondMember);
        List<Future<Void>> futures = new ArrayList<>();
        for (User user : leavers) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                go.await();
                webTestClient
                        .mutateWith(loginAs(user))
                        .post()
                        .uri("/topics/{id}/leave", topic.getId())
                        .exchange()
                        .expectStatus()
                        .isEqualTo(HttpStatus.SEE_OTHER);
                return null;
            }));
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(groupRepository.findByTopicIdAndStatus(topic.getId(), GroupStatus.ACTIVE).blockOptional())
                .isEmpty();
        var disbanded = groupRepository.findById(group.getId()).block();
        assertThat(disbanded.getStatus()).isEqualTo(GroupStatus.DISBANDED);
        assertThat(groupService.activeMemberCount(group.getId()).block()).isEqualTo(0);
    }

    // --- Test helpers ------------------------------------------------------------------------------

    private String detailBody(User user, UUID topicId) {
        return webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/topics/{id}", topicId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private void setSkillVisibility(boolean enabled) {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setSkillVisibilityEnabled(enabled);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    private void setDirectoryAudience(DirectoryAudience audience) {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setParticipantsDirectoryAudience(audience);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    private UUID persistSkill(String name) {
        return databaseClient
                .sql("INSERT INTO skills (name) VALUES (:name) RETURNING id")
                .bind("name", name)
                .map(row -> row.get("id", UUID.class))
                .one()
                .block();
    }

    private void assignSkill(UUID participantId, UUID skillId) {
        databaseClient
                .sql("INSERT INTO participant_skills (participant_id, skill_id) VALUES (:pid, :sid)")
                .bind("pid", participantId)
                .bind("sid", skillId)
                .then()
                .block();
    }

    private void insertFreeTextValue(UUID participantId, UUID definitionId, String value) {
        databaseClient
                .sql(
                        "INSERT INTO custom_field_values"
                                + " (participant_id, custom_field_definition_id, free_text_value)"
                                + " VALUES (:pid, :fid, :value)")
                .bind("pid", participantId)
                .bind("fid", definitionId)
                .bind("value", value)
                .then()
                .block();
    }

    private CustomFieldDefinition persistDefinition(String label, boolean public_) {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setLabel(label);
        definition.setFieldType(CustomFieldType.FREE_TEXT);
        definition.setRequired(false);
        definition.setPublic_(public_);
        definition.setOverview(false);
        definition.setEnabled(true);
        Instant now = Instant.now();
        definition.setCreatedAt(now);
        definition.setUpdatedAt(now);
        return customFieldDefinitionRepository.save(definition).block();
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

    private Topic persistTopic(UUID creatorUserId, TopicApprovalStatus status) {
        Topic topic = new Topic();
        topic.setName("Topic " + UUID.randomUUID());
        topic.setDescription("Description " + UUID.randomUUID());
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

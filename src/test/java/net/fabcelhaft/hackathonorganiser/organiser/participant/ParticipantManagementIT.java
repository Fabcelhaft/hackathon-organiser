package net.fabcelhaft.hackathonorganiser.organiser.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinition;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinitionRepository;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldOption;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldOptionRepository;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldType;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupRepository;
import net.fabcelhaft.hackathonorganiser.group.GroupStatus;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantRepository;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.skill.Skill;
import net.fabcelhaft.hackathonorganiser.skill.SkillRepository;
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
import org.springframework.r2dbc.core.DatabaseClient;
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
 * Integration tests for User Story 3's Participant management (T034), covering
 * specs/002-core-domain-model/contracts/participant-management.md end to end against the real
 * {@code SecurityWebFilterChain} and repositories — no hand-mocked security substitute
 * (research.md §6), following the same {@code WebTestClient} + {@code mockOidcLogin()} +
 * Testcontainers pattern as the other {@code organiser.*} integration tests in this feature.
 */
@SpringBootTest
@Testcontainers
class ParticipantManagementIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @Autowired
    ApplicationContext applicationContext;

    WebTestClient webTestClient;

    @BeforeEach
    void setUpWebTestClient() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    @Autowired
    UserRepository userRepository;

    @Autowired
    ParticipantRepository participantRepository;

    @Autowired
    SkillRepository skillRepository;

    @Autowired
    CustomFieldDefinitionRepository customFieldDefinitionRepository;

    @Autowired
    CustomFieldOptionRepository customFieldOptionRepository;

    @Autowired
    TopicRepository topicRepository;

    @Autowired
    GroupRepository groupRepository;

    @Autowired
    DatabaseClient databaseClient;

    // --- Registration (FR-006a, FR-006b) ---------------------------------------------------------

    @Test
    void organiserCanRegisterAUserAsAParticipantWithInitialStatusActive() {
        User user = persistUser("Registrant " + UUID.randomUUID());

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/participants")
                .body(BodyInserters.fromFormData("user_id", user.getId().toString()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        Participant saved = participantRepository.findByUserId(user.getId()).block();
        assertThat(saved).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(ParticipantStatus.ACTIVE);
    }

    @Test
    void registrationRejectsASecondParticipantForTheSameUser() {
        User user = persistUser("Double Registrant " + UUID.randomUUID());
        persistParticipant(user.getId(), ParticipantStatus.ACTIVE);

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/participants")
                .body(BodyInserters.fromFormData("user_id", user.getId().toString()))
                .exchange()
                .expectStatus().isOk(); // form re-rendered with error, not redirected
    }

    @Test
    void registrationRejectsAnUnknownUserId() {
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/participants")
                .body(BodyInserters.fromFormData("user_id", UUID.randomUUID().toString()))
                .exchange()
                .expectStatus().isOk(); // form re-rendered with error, not redirected
    }

    // --- Detail view ------------------------------------------------------------------------------

    @Test
    void organiserCanViewParticipantDetailAndUnknownReturnsNotFound() {
        User user = persistUser("Detail Target " + UUID.randomUUID());
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);

        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/participants/{id}", participant.getId())
                .exchange()
                .expectStatus().isOk();

        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/participants/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void organiserCanViewTheRegistrationForm() {
        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/participants/new")
                .exchange()
                .expectStatus().isOk();
    }

    // --- Status change (FR-007) -------------------------------------------------------------------

    @Test
    void organiserCanSetStatusToEachOfTheThreeValues() {
        User user = persistUser("Status Target " + UUID.randomUUID());
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);

        for (ParticipantStatus status : ParticipantStatus.values()) {
            webTestClient.mutateWith(organiser())
                    .post().uri("/organiser/participants/{id}/status", participant.getId())
                    .body(BodyInserters.fromFormData("status", status.name()))
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.SEE_OTHER)
                    .expectHeader().valueEquals("Location", "/organiser/participants/" + participant.getId());

            assertThat(participantRepository.findById(participant.getId()).block().getStatus())
                    .isEqualTo(status);
        }
    }

    @Test
    void changingStatusOfAnUnknownParticipantReturnsNotFound() {
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/participants/{id}/status", UUID.randomUUID())
                .body(BodyInserters.fromFormData("status", "REVOKED"))
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- Audit (T026, Story 2, FR-005-FR-008, FR-011) --------------------------------------------

    @Test
    void organiserCanViewAnAuditHistoryListingPreviouslyRecordedEntriesMostRecentFirst() {
        User user = persistUser("Audit Target " + UUID.randomUUID());
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/participants/{id}/status", participant.getId())
                .body(BodyInserters.fromFormData("status", "REVOKED"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/participants/{id}/status", participant.getId())
                .body(BodyInserters.fromFormData("status", "ACTIVE"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        String body = webTestClient.mutateWith(organiser())
                .get().uri("/organiser/participants/{id}/audit", participant.getId())
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("STATUS_CHANGED");
        assertThat(body).contains("ACTIVE -&gt; REVOKED");
        assertThat(body).contains("REVOKED -&gt; ACTIVE");
        // Most-recent-first: the later REVOKED-to-ACTIVE row precedes the earlier one.
        assertThat(body.indexOf("REVOKED -&gt; ACTIVE")).isLessThan(body.indexOf("ACTIVE -&gt; REVOKED"));
    }

    @Test
    void auditHistoryRendersAnEmptyLabeledStateForAParticipantWithNoEntriesYet() {
        User user = persistUser("Untouched Participant " + UUID.randomUUID());
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);

        String body = webTestClient.mutateWith(organiser())
                .get().uri("/organiser/participants/{id}/audit", participant.getId())
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).containsIgnoringCase("no changes recorded yet");
    }

    @Test
    void auditHistoryIsDeniedToANonOrganiserBeforeAnyContentRenders() {
        User user = persistUser("Denied Audit Participant " + UUID.randomUUID());
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);

        webTestClient.mutateWith(standardUser())
                .get().uri("/organiser/participants/{id}/audit", participant.getId())
                .exchange()
                .expectStatus().isForbidden();

        webTestClient
                .get().uri("/organiser/participants/{id}/audit", participant.getId())
                .exchange()
                .expectStatus().is3xxRedirection();
    }

    @Test
    void auditHistoryOfAnUnknownParticipantReturnsNotFound() {
        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/participants/{id}/audit", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- Skill assignment (FR-009) ------------------------------------------------------------------

    @Test
    void organiserCanAssignAndReplaceSkillSelections() {
        User user = persistUser("Skill Target " + UUID.randomUUID());
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        Skill skillA = persistSkill("Skill A " + UUID.randomUUID());
        Skill skillB = persistSkill("Skill B " + UUID.randomUUID());

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/participants/{id}/skills", participant.getId())
                .body(BodyInserters.fromFormData("skill_ids", skillA.getId().toString()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(checkboxTag(detailBody(participant.getId()), skillA.getId())).contains("checked");

        // Replace the selection entirely with skillB.
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/participants/{id}/skills", participant.getId())
                .body(BodyInserters.fromFormData("skill_ids", skillB.getId().toString()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        String bodyAfterReplace = detailBody(participant.getId());
        assertThat(checkboxTag(bodyAfterReplace, skillB.getId())).contains("checked");
        // The previously-selected skillA's checkbox must no longer be checked.
        assertThat(checkboxTag(bodyAfterReplace, skillA.getId())).doesNotContain("checked");
    }

    @Test
    void skillAssignmentWithAnUnknownSkillIdReturnsNotFound() {
        User user = persistUser("Skill Guard Target " + UUID.randomUUID());
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/participants/{id}/skills", participant.getId())
                .body(BodyInserters.fromFormData("skill_ids", UUID.randomUUID().toString()))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void skillAssignmentOnAnUnknownParticipantReturnsNotFound() {
        Skill skill = persistSkill("Orphan Skill " + UUID.randomUUID());

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/participants/{id}/skills", UUID.randomUUID())
                .body(BodyInserters.fromFormData("skill_ids", skill.getId().toString()))
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- Custom Field values (FR-013, FR-014) -----------------------------------------------------

    @Test
    void organiserCanSetAFreeTextCustomFieldValue() {
        User user = persistUser("Free Text Target " + UUID.randomUUID());
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        CustomFieldDefinition field =
                persistDefinition("T-Shirt Size " + UUID.randomUUID(), CustomFieldType.FREE_TEXT, false);

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/participants/{id}/custom-fields/{fieldId}", participant.getId(), field.getId())
                .body(BodyInserters.fromFormData("value", "Medium"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(detailBody(participant.getId())).contains("Medium");
    }

    @Test
    void organiserCanSetAMultiSelectCustomFieldValue() {
        User user = persistUser("Multi Select Target " + UUID.randomUUID());
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        CustomFieldDefinition field =
                persistDefinition("Languages " + UUID.randomUUID(), CustomFieldType.MULTI_SELECT, false);
        CustomFieldOption option = persistOption(field.getId(), "Java " + UUID.randomUUID());

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/participants/{id}/custom-fields/{fieldId}", participant.getId(), field.getId())
                .body(BodyInserters.fromFormData("option_ids", option.getId().toString()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(checkboxTag(detailBody(participant.getId()), option.getId())).contains("checked");
    }

    @Test
    void customFieldValueRejectsOptionIdsSubmittedForAFreeTextField() {
        User user = persistUser("Shape Mismatch A " + UUID.randomUUID());
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        CustomFieldDefinition freeTextField =
                persistDefinition("Free Text Field " + UUID.randomUUID(), CustomFieldType.FREE_TEXT, false);

        webTestClient.mutateWith(organiser())
                .post().uri(
                        "/organiser/participants/{id}/custom-fields/{fieldId}",
                        participant.getId(),
                        freeTextField.getId())
                .body(BodyInserters.fromFormData("option_ids", UUID.randomUUID().toString()))
                .exchange()
                .expectStatus().isOk(); // detail re-rendered with a validation error, not redirected
    }

    @Test
    void customFieldValueRejectsAFreeTextValueSubmittedForAMultiSelectField() {
        User user = persistUser("Shape Mismatch B " + UUID.randomUUID());
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        CustomFieldDefinition multiSelectField =
                persistDefinition("Multi Select Field " + UUID.randomUUID(), CustomFieldType.MULTI_SELECT, false);

        webTestClient.mutateWith(organiser())
                .post().uri(
                        "/organiser/participants/{id}/custom-fields/{fieldId}",
                        participant.getId(),
                        multiSelectField.getId())
                .body(BodyInserters.fromFormData("value", "not allowed here"))
                .exchange()
                .expectStatus().isOk(); // detail re-rendered with a validation error, not redirected
    }

    @Test
    void customFieldValueRejectsAnOptionIdNotBelongingToTheField() {
        User user = persistUser("Foreign Option Target " + UUID.randomUUID());
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        CustomFieldDefinition field =
                persistDefinition("Languages " + UUID.randomUUID(), CustomFieldType.MULTI_SELECT, false);
        persistOption(field.getId(), "Java " + UUID.randomUUID());
        // An option id that simply doesn't exist for this (or any) definition.
        UUID foreignOptionId = UUID.randomUUID();

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/participants/{id}/custom-fields/{fieldId}", participant.getId(), field.getId())
                .body(BodyInserters.fromFormData("option_ids", foreignOptionId.toString()))
                .exchange()
                .expectStatus().isOk(); // detail re-rendered with a validation error, not redirected
    }

    @Test
    void customFieldValueOnAnUnknownParticipantOrFieldReturnsNotFound() {
        User user = persistUser("Not Found Target " + UUID.randomUUID());
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        CustomFieldDefinition field =
                persistDefinition("Some Field " + UUID.randomUUID(), CustomFieldType.FREE_TEXT, false);

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/participants/{id}/custom-fields/{fieldId}", UUID.randomUUID(), field.getId())
                .body(BodyInserters.fromFormData("value", "x"))
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.mutateWith(organiser())
                .post().uri(
                        "/organiser/participants/{id}/custom-fields/{fieldId}",
                        participant.getId(),
                        UUID.randomUUID())
                .body(BodyInserters.fromFormData("value", "x"))
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- Delete (Participant only, blocked while in a Group) -----------------------------------

    @Test
    void organiserCanDeleteAParticipantNotInAGroup() {
        User user = persistUser("Deletable Target " + UUID.randomUUID());
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/participants/{id}/delete", participant.getId())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER)
                .expectHeader().valueEquals("Location", "/organiser/participants");

        assertThat(participantRepository.findById(participant.getId()).block()).isNull();
        // The User account itself must survive the Participant's deletion.
        assertThat(userRepository.findById(user.getId()).block()).isNotNull();
    }

    @Test
    void deletingAParticipantWhoIsInAGroupIsBlocked() {
        User user = persistUser("Grouped Target " + UUID.randomUUID());
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        Topic topic = persistTopic("Delete Guard Topic " + UUID.randomUUID());
        Group group = persistActiveGroup(topic.getId());
        addMemberDirectly(group.getId(), participant.getId());

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/participants/{id}/delete", participant.getId())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);

        assertThat(participantRepository.findById(participant.getId()).block()).isNotNull();
    }

    @Test
    void deletingAnUnknownParticipantRedirectsWithoutError() {
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/participants/{id}/delete", UUID.randomUUID())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);
    }

    @Test
    void listViewHidesDeleteForAParticipantCurrentlyInAGroup() {
        User user = persistUser("List Grouped Target " + UUID.randomUUID());
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        Topic topic = persistTopic("Delete Guard List Topic " + UUID.randomUUID());
        Group group = persistActiveGroup(topic.getId());
        addMemberDirectly(group.getId(), participant.getId());

        String row = listRowFor(user.getDisplayName());
        assertThat(row).doesNotContain("delete-open-button");
        assertThat(row).contains("In a Group");

        String body = webTestClient.mutateWith(organiser())
                .get().uri("/organiser/participants")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).doesNotContain("/organiser/participants/" + participant.getId() + "/delete");
    }

    // --- Incomplete indicator on the list view (FR-027, SC-007) -------------------------------------

    @Test
    void listViewFlagsAnIncompleteParticipantAndClearsOnceTheRequiredValueIsSet() {
        // Deliberately avoids the substrings "Incomplete"/"Complete" in the display name itself —
        // otherwise the name would collide with the very indicator text this test asserts on.
        User user = persistUser("Required Field Target " + UUID.randomUUID());
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        CustomFieldDefinition requiredField =
                persistDefinition("Required Field " + UUID.randomUUID(), CustomFieldType.FREE_TEXT, true);

        String rowWhileUnmet = listRowFor(user.getDisplayName());
        assertThat(rowWhileUnmet).contains("Incomplete");

        webTestClient.mutateWith(organiser())
                .post().uri(
                        "/organiser/participants/{id}/custom-fields/{fieldId}",
                        participant.getId(),
                        requiredField.getId())
                .body(BodyInserters.fromFormData("value", "Filled in"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        String rowAfterFilled = listRowFor(user.getDisplayName());
        assertThat(rowAfterFilled).doesNotContain("Incomplete");
        assertThat(rowAfterFilled).contains("Complete");
    }

    // --- Non-Organiser denied on every route (FR-022, SC-004) -----------------------------------

    @Test
    void nonOrganiserIsDeniedOnEveryRoute() {
        User user = persistUser("Guarded User " + UUID.randomUUID());
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        CustomFieldDefinition field =
                persistDefinition("Guarded Field " + UUID.randomUUID(), CustomFieldType.FREE_TEXT, false);

        webTestClient.mutateWith(standardUser())
                .get().uri("/organiser/participants")
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .get().uri("/organiser/participants/new")
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/participants")
                .body(BodyInserters.fromFormData("user_id", user.getId().toString()))
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .get().uri("/organiser/participants/{id}", participant.getId())
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/participants/{id}/status", participant.getId())
                .body(BodyInserters.fromFormData("status", "REVOKED"))
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/participants/{id}/skills", participant.getId())
                .body(BodyInserters.fromFormData("skill_ids", UUID.randomUUID().toString()))
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/participants/{id}/custom-fields/{fieldId}", participant.getId(), field.getId())
                .body(BodyInserters.fromFormData("value", "Nope"))
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/participants/{id}/delete", participant.getId())
                .exchange()
                .expectStatus().isForbidden();
    }

    // --- Test helpers ----------------------------------------------------------------------------

    private String detailBody(UUID participantId) {
        return webTestClient.mutateWith(organiser())
                .get().uri("/organiser/participants/{id}", participantId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    /** Isolates the single checkbox {@code <input .../>} tag whose value is the given id. */
    private String checkboxTag(String body, UUID valueId) {
        int idx = body.indexOf(valueId.toString());
        assertThat(idx).describedAs("expected to find a checkbox for " + valueId).isPositive();
        return body.substring(idx, body.indexOf("/>", idx));
    }

    private String listRowFor(String displayName) {
        String body = webTestClient.mutateWith(organiser())
                .get().uri("/organiser/participants")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        int start = body.indexOf(displayName);
        assertThat(start).describedAs("expected to find a list row for " + displayName).isPositive();
        int end = body.indexOf("</tr>", start);
        return body.substring(start, end);
    }

    private User persistUser(String displayName) {
        return persistUser(displayName, false);
    }

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

    private Participant persistParticipant(UUID userId, ParticipantStatus status) {
        Participant participant = new Participant();
        participant.setUserId(userId);
        participant.setStatus(status);
        participant.setCreatedAt(Instant.now());
        participant.setUpdatedAt(Instant.now());
        return participantRepository.save(participant).block();
    }

    private Skill persistSkill(String name) {
        Skill skill = new Skill();
        skill.setName(name);
        skill.setCreatedAt(Instant.now());
        skill.setUpdatedAt(Instant.now());
        return skillRepository.save(skill).block();
    }

    private CustomFieldDefinition persistDefinition(String label, CustomFieldType type, boolean required) {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setLabel(label);
        definition.setFieldType(type);
        definition.setRequired(required);
        definition.setCreatedAt(Instant.now());
        definition.setUpdatedAt(Instant.now());
        return customFieldDefinitionRepository.save(definition).block();
    }

    private Topic persistTopic(String name) {
        User creator = persistUser("Creator for " + name);
        Topic topic = new Topic();
        topic.setName(name);
        topic.setDescription("Desc");
        topic.setCreatedByUserId(creator.getId());
        topic.setCreatedAt(Instant.now());
        topic.setUpdatedAt(Instant.now());
        return topicRepository.save(topic).block();
    }

    private Group persistActiveGroup(UUID topicId) {
        Group group = new Group();
        group.setTopicId(topicId);
        group.setStatus(GroupStatus.ACTIVE);
        group.setCreatedAt(Instant.now());
        group.setUpdatedAt(Instant.now());
        return groupRepository.save(group).block();
    }

    private void addMemberDirectly(UUID groupId, UUID participantId) {
        databaseClient
                .sql("INSERT INTO group_members (group_id, participant_id, active, joined_at)"
                        + " VALUES (:gid, :pid, true, :now)")
                .bind("gid", groupId)
                .bind("pid", participantId)
                .bind("now", Instant.now())
                .then()
                .block();
    }

    private CustomFieldOption persistOption(UUID definitionId, String label) {
        CustomFieldOption option = new CustomFieldOption();
        option.setCustomFieldDefinitionId(definitionId);
        option.setLabel(label);
        option.setCreatedAt(Instant.now());
        option.setUpdatedAt(Instant.now());
        return customFieldOptionRepository.save(option).block();
    }

    /**
     * A real, persisted Organiser login (feature 006, FR-001-FR-002a): {@code register}/{@code
     * changeStatus}/{@code replaceSkills}/{@code delete}/{@code setCustomFieldValue} now resolve
     * {@code @AuthenticationPrincipal HackathonOidcUser} to attribute an {@link
     * net.fabcelhaft.hackathonorganiser.audit.AuditEntry}'s {@code actor_user_id} (a real FK to
     * {@code users}), so a bare {@code mockOidcLogin()} (no backing User row) is no longer
     * sufficient for any route on this controller.
     */
    private OidcLoginMutator organiser() {
        return loginAsUser(persistUser("Organiser " + UUID.randomUUID(), true));
    }

    private OidcLoginMutator standardUser() {
        return loginAsUser(persistUser("Standard User " + UUID.randomUUID(), false));
    }

    private static OidcLoginMutator loginAsUser(User user) {
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

package net.fabcelhaft.hackathonorganiser.organiser.customfield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceService;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinition;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinitionRepository;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldOption;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldOptionRepository;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldType;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantRepository;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.OidcLoginMutator;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration tests for User Story 2's Custom Field Definition catalog (T019), covering
 * specs/002-core-domain-model/contracts/catalog-management.md end to end against the real
 * {@code SecurityWebFilterChain} and repositories — no hand-mocked security substitute
 * (research.md §6), following the same {@code WebTestClient} + {@code mockOidcLogin()} +
 * Testcontainers pattern as {@code organiser.user.UserManagementIT}.
 *
 * <p>{@code custom_field_values}/{@code custom_field_value_options} now exist as of User Story 3
 * (T038). The "not locked -> type change succeeds" and "not referenced -> delete succeeds" paths
 * continue to be exercised here (originally proving {@code CustomFieldService}'s defensive
 * "relation does not exist" handling before these tables existed; now simply proving the ordinary
 * happy path still holds now that the tables are real). The "locked/blocked once a value exists"
 * side of both guards — previously only unit-tested with mocks in {@code CustomFieldServiceTest}
 * because no real Participant/value machinery existed yet — is additionally proven here
 * end-to-end against a real Postgres row in {@code custom_field_values}/
 * {@code custom_field_value_options}
 * ({@link #typeChangeIsBlockedOnceAParticipantValueExists()},
 * {@link #definitionDeleteIsBlockedOnceAParticipantValueExists()},
 * {@link #optionDeleteIsBlockedOnceAParticipantHasSelectedIt()}).
 */
@SpringBootTest
@Testcontainers
class CustomFieldManagementIT {

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
    CustomFieldDefinitionRepository definitionRepository;

    @Autowired
    CustomFieldOptionRepository optionRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ParticipantRepository participantRepository;

    @Autowired
    DatabaseClient databaseClient;

    @Autowired
    ComplianceService complianceService;

    // --- Listing --------------------------------------------------------------------------------

    @Test
    void organiserCanListAndViewCustomFieldForms() {
        persistDefinition("List Target " + UUID.randomUUID(), CustomFieldType.FREE_TEXT, false);

        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/custom-fields")
                .exchange()
                .expectStatus().isOk();

        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/custom-fields/new")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void editingAnUnknownCustomFieldReturnsNotFound() {
        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/custom-fields/{id}/edit", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- Create: free-text and multi-select (FR-011, FR-012) -----------------------------------

    @Test
    void organiserCanCreateAFreeTextCustomField() {
        String label = "T-Shirt Size " + UUID.randomUUID();

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields")
                .body(BodyInserters.fromFormData("label", label)
                        .with("fieldType", "FREE_TEXT")
                        .with("required", "true"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER)
                .expectHeader().valueEquals("Location", "/organiser/custom-fields");

        CustomFieldDefinition saved = definitionRepository.findAll().collectList().block().stream()
                .filter(d -> d.getLabel().equals(label))
                .findFirst()
                .orElseThrow();
        assertThat(saved.getFieldType()).isEqualTo(CustomFieldType.FREE_TEXT);
        assertThat(saved.isRequired()).isTrue();
    }

    @Test
    void organiserCanCreateAMultiSelectCustomFieldWithOptions() {
        String label = "Languages " + UUID.randomUUID();

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields")
                .body(BodyInserters.fromFormData("label", label)
                        .with("fieldType", "MULTI_SELECT")
                        .with("options", "Java")
                        .with("options", "Python"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        CustomFieldDefinition saved = definitionRepository.findAll().collectList().block().stream()
                .filter(d -> d.getLabel().equals(label))
                .findFirst()
                .orElseThrow();
        assertThat(saved.getFieldType()).isEqualTo(CustomFieldType.MULTI_SELECT);
        assertThat(optionRepository.findByCustomFieldDefinitionId(saved.getId()).collectList().block())
                .extracting(CustomFieldOption::getLabel)
                .containsExactlyInAnyOrder("Java", "Python");
    }

    @Test
    void createRejectsMultiSelectWithZeroOptions() {
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields")
                .body(BodyInserters.fromFormData("label", "No Options " + UUID.randomUUID())
                        .with("fieldType", "MULTI_SELECT"))
                .exchange()
                .expectStatus().isOk(); // form re-rendered with error, not redirected
    }

    // --- Update: label/required, and an unlocked type change --------------------------------------

    @Test
    void organiserCanUpdateLabelAndRequiredFlag() {
        CustomFieldDefinition definition =
                persistDefinition("Old Label " + UUID.randomUUID(), CustomFieldType.FREE_TEXT, false);
        String newLabel = "New Label " + UUID.randomUUID();

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields/{id}", definition.getId())
                .body(BodyInserters.fromFormData("label", newLabel)
                        .with("required", "true")
                        .with("fieldType", "FREE_TEXT"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        CustomFieldDefinition updated =
                definitionRepository.findById(definition.getId()).block();
        assertThat(updated.getLabel()).isEqualTo(newLabel);
        assertThat(updated.isRequired()).isTrue();
    }

    @Test
    void typeChangeSucceedsWhenNoParticipantValueExistsYet() {
        // No Participant/custom-field-value machinery exists yet in this story (User Story 3), so
        // every definition here is necessarily unlocked — this proves the "no value recorded"
        // path of FR-012a end-to-end against the real database.
        CustomFieldDefinition definition =
                persistDefinition("Changeable " + UUID.randomUUID(), CustomFieldType.FREE_TEXT, false);

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields/{id}", definition.getId())
                .body(BodyInserters.fromFormData("label", definition.getLabel())
                        .with("required", "false")
                        .with("fieldType", "MULTI_SELECT"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(definitionRepository.findById(definition.getId()).block().getFieldType())
                .isEqualTo(CustomFieldType.MULTI_SELECT);
    }

    @Test
    void updatingAnUnknownCustomFieldReturnsNotFound() {
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields/{id}", UUID.randomUUID())
                .body(BodyInserters.fromFormData("label", "Whatever").with("fieldType", "FREE_TEXT"))
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- Delete: not-referenced path succeeds (FR-023) -----------------------------------------

    @Test
    void organiserCanDeleteACustomFieldThatIsNotReferenced() {
        CustomFieldDefinition definition =
                persistDefinition("Deletable " + UUID.randomUUID(), CustomFieldType.FREE_TEXT, false);

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields/{id}/delete", definition.getId())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(definitionRepository.findById(definition.getId()).block()).isNull();
    }

    // --- Options: add / duplicate / remove -------------------------------------------------------

    @Test
    void organiserCanAddAndRemoveAnOption() {
        CustomFieldDefinition definition =
                persistDefinition("Languages " + UUID.randomUUID(), CustomFieldType.MULTI_SELECT, false);
        String optionLabel = "Java " + UUID.randomUUID();

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields/{id}/options", definition.getId())
                .body(BodyInserters.fromFormData("label", optionLabel))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER)
                .expectHeader().valueEquals("Location", "/organiser/custom-fields/" + definition.getId() + "/edit");

        CustomFieldOption saved = optionRepository
                .findByCustomFieldDefinitionId(definition.getId())
                .collectList()
                .block()
                .stream()
                .filter(o -> o.getLabel().equals(optionLabel))
                .findFirst()
                .orElseThrow();

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields/{id}/options/{optionId}/delete", definition.getId(), saved.getId())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(optionRepository.findById(saved.getId()).block()).isNull();
    }

    @Test
    void addingADuplicateOptionLabelIsRejectedCaseInsensitively() {
        CustomFieldDefinition definition =
                persistDefinition("Languages " + UUID.randomUUID(), CustomFieldType.MULTI_SELECT, false);
        persistOption(definition.getId(), "Java");

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields/{id}/options", definition.getId())
                .body(BodyInserters.fromFormData("label", "JAVA"))
                .exchange()
                .expectStatus().isOk(); // form re-rendered with error, not redirected

        assertThat(optionRepository.findByCustomFieldDefinitionId(definition.getId()).collectList().block())
                .hasSize(1);
    }

    // --- Guards genuinely block now that custom_field_values/_options exist (FR-012a, FR-023, FR-012b)

    @Test
    void typeChangeIsBlockedOnceAParticipantValueExists() {
        CustomFieldDefinition definition =
                persistDefinition("Locked " + UUID.randomUUID(), CustomFieldType.FREE_TEXT, false);
        Participant participant = persistParticipant();
        insertFreeTextValue(participant.getId(), definition.getId(), "Medium");

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields/{id}", definition.getId())
                .body(BodyInserters.fromFormData("label", definition.getLabel())
                        .with("required", "false")
                        .with("fieldType", "MULTI_SELECT"))
                .exchange()
                .expectStatus().isOk(); // form re-rendered with error, not redirected

        assertThat(definitionRepository.findById(definition.getId()).block().getFieldType())
                .isEqualTo(CustomFieldType.FREE_TEXT);
    }

    @Test
    void definitionDeleteIsBlockedOnceAParticipantValueExists() {
        CustomFieldDefinition definition =
                persistDefinition("Referenced " + UUID.randomUUID(), CustomFieldType.FREE_TEXT, false);
        Participant participant = persistParticipant();
        insertFreeTextValue(participant.getId(), definition.getId(), "Medium");

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields/{id}/delete", definition.getId())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);

        assertThat(definitionRepository.findById(definition.getId()).block()).isNotNull();
    }

    @Test
    void definitionDeleteIsBlockedWhileAComplianceDiversityRequirementReferencesIt() {
        // Feature 005 (research.md §8, Edge Cases): the delete-guard extends to compliance rules.
        CustomFieldDefinition definition =
                persistDefinition("Compliance Referenced " + UUID.randomUUID(), CustomFieldType.FREE_TEXT, false);
        complianceService.addRequirement(definition.getId(), 2).block();

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields/{id}/delete", definition.getId())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);

        assertThat(definitionRepository.findById(definition.getId()).block()).isNotNull();
    }

    @Test
    void optionDeleteIsBlockedOnceAParticipantHasSelectedIt() {
        CustomFieldDefinition definition =
                persistDefinition("Referenced Options " + UUID.randomUUID(), CustomFieldType.MULTI_SELECT, false);
        CustomFieldOption option = persistOption(definition.getId(), "Java " + UUID.randomUUID());
        Participant participant = persistParticipant();
        insertOptionSelection(participant.getId(), definition.getId(), option.getId());

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields/{id}/options/{optionId}/delete", definition.getId(), option.getId())
                .exchange()
                .expectStatus().isOk(); // detail form re-rendered with error, not redirected

        assertThat(optionRepository.findById(option.getId()).block()).isNotNull();
    }

    // --- User Story 3: SINGLE_SELECT, Public/Overview, Country (FR-012, FR-013, FR-015, FR-016) ---

    @Test
    void creatingASingleSelectFieldRequiresAtLeastOneOptionThenAppearsOnRegisterRestrictedToOneChoice() {
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields")
                .body(BodyInserters.fromFormData("label", "Size " + UUID.randomUUID()).with("fieldType", "SINGLE_SELECT"))
                .exchange()
                .expectStatus().isOk(); // rejected: zero options

        String label = "Size " + UUID.randomUUID();
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields")
                .body(BodyInserters.fromFormData("label", label)
                        .with("fieldType", "SINGLE_SELECT")
                        .with("options", "S")
                        .with("options", "L"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        CustomFieldDefinition saved = definitionRepository.findAll().collectList().block().stream()
                .filter(d -> d.getLabel().equals(label))
                .findFirst()
                .orElseThrow();
        assertThat(saved.getFieldType()).isEqualTo(CustomFieldType.SINGLE_SELECT);

        String registerBody = webTestClient.mutateWith(loginAs(persistStandardUser()))
                .get().uri("/register")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(registerBody).contains(label);
    }

    @Test
    void publicAndOverviewCheckboxesAreIndependentlySettableAndPersist() {
        CustomFieldDefinition definition =
                persistDefinition("Bio " + UUID.randomUUID(), CustomFieldType.FREE_TEXT, false);

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields/{id}", definition.getId())
                .body(BodyInserters.fromFormData("label", definition.getLabel())
                        .with("required", "false")
                        .with("fieldType", "FREE_TEXT")
                        .with("overview", "true"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        CustomFieldDefinition updated = definitionRepository.findById(definition.getId()).block();
        assertThat(updated.isPublic_()).isFalse();
        assertThat(updated.isOverview()).isTrue();
    }

    @Test
    void enablingCountryMakesItASearchableFieldOnRegisterAndDisablingRemovesItButKeepsRecordedValues() {
        CustomFieldDefinition country = definitionRepository
                .findAll()
                .filter(d -> d.getFieldType() == CustomFieldType.COUNTRY)
                .blockFirst();

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields/{id}/country/enable", country.getId())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(definitionRepository.findById(country.getId()).block().isEnabled()).isTrue();

        String registerBodyEnabled = webTestClient.mutateWith(loginAs(persistStandardUser()))
                .get().uri("/register")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(registerBodyEnabled).contains("Country");
        assertThat(registerBodyEnabled).contains("Germany"); // from IsoCountryCatalog

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields/{id}/country/disable", country.getId())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(definitionRepository.findById(country.getId()).block().isEnabled()).isFalse();
    }

    @Test
    void changingOrDeletingTheCountryRowIsRejected() {
        CustomFieldDefinition country = definitionRepository
                .findAll()
                .filter(d -> d.getFieldType() == CustomFieldType.COUNTRY)
                .blockFirst();

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields/{id}", country.getId())
                .body(BodyInserters.fromFormData("label", "Country").with("required", "false").with("fieldType", "FREE_TEXT"))
                .exchange()
                .expectStatus().isOk(); // rejected: Country's type cannot change

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/custom-fields/{id}/delete", country.getId())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);

        assertThat(definitionRepository.findById(country.getId()).block()).isNotNull();
    }

    // --- Non-Organiser denied on every route (FR-022, SC-004) -----------------------------------

    @Test
    void nonOrganiserIsDeniedOnEveryRoute() {
        CustomFieldDefinition definition =
                persistDefinition("Guarded " + UUID.randomUUID(), CustomFieldType.MULTI_SELECT, false);
        CustomFieldOption option = persistOption(definition.getId(), "Guarded Option " + UUID.randomUUID());

        webTestClient.mutateWith(standardUser())
                .get().uri("/organiser/custom-fields")
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .get().uri("/organiser/custom-fields/new")
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/custom-fields")
                .body(BodyInserters.fromFormData("label", "Nope").with("fieldType", "FREE_TEXT"))
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .get().uri("/organiser/custom-fields/{id}/edit", definition.getId())
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/custom-fields/{id}", definition.getId())
                .body(BodyInserters.fromFormData("label", "Nope").with("fieldType", "MULTI_SELECT"))
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/custom-fields/{id}/delete", definition.getId())
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/custom-fields/{id}/options", definition.getId())
                .body(BodyInserters.fromFormData("label", "Nope"))
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/custom-fields/{id}/options/{optionId}/delete", definition.getId(), option.getId())
                .exchange()
                .expectStatus().isForbidden();

        CustomFieldDefinition country = definitionRepository
                .findAll()
                .filter(d -> d.getFieldType() == CustomFieldType.COUNTRY)
                .blockFirst();
        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/custom-fields/{id}/country/enable", country.getId())
                .exchange()
                .expectStatus().isForbidden();
        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/custom-fields/{id}/country/disable", country.getId())
                .exchange()
                .expectStatus().isForbidden();
    }

    // --- Test helpers ----------------------------------------------------------------------------

    private CustomFieldDefinition persistDefinition(String label, CustomFieldType type, boolean required) {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setLabel(label);
        definition.setFieldType(type);
        definition.setRequired(required);
        definition.setCreatedAt(Instant.now());
        definition.setUpdatedAt(Instant.now());
        return definitionRepository.save(definition).block();
    }

    private CustomFieldOption persistOption(UUID definitionId, String label) {
        CustomFieldOption option = new CustomFieldOption();
        option.setCustomFieldDefinitionId(definitionId);
        option.setLabel(label);
        option.setCreatedAt(Instant.now());
        option.setUpdatedAt(Instant.now());
        return optionRepository.save(option).block();
    }

    private Participant persistParticipant() {
        User user = new User();
        user.setOidcSubject("sub-" + UUID.randomUUID());
        user.setDisplayName("Guard User " + UUID.randomUUID());
        user.setOrganiser(false);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        User savedUser = userRepository.save(user).block();

        Participant participant = new Participant();
        participant.setUserId(savedUser.getId());
        participant.setStatus(ParticipantStatus.ACTIVE);
        participant.setCreatedAt(Instant.now());
        participant.setUpdatedAt(Instant.now());
        return participantRepository.save(participant).block();
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

    private void insertOptionSelection(UUID participantId, UUID definitionId, UUID optionId) {
        databaseClient
                .sql(
                        "INSERT INTO custom_field_values"
                                + " (participant_id, custom_field_definition_id, free_text_value)"
                                + " VALUES (:pid, :fid, NULL)")
                .bind("pid", participantId)
                .bind("fid", definitionId)
                .then()
                .block();
        databaseClient
                .sql(
                        "INSERT INTO custom_field_value_options"
                                + " (participant_id, custom_field_definition_id, custom_field_option_id)"
                                + " VALUES (:pid, :fid, :oid)")
                .bind("pid", participantId)
                .bind("fid", definitionId)
                .bind("oid", optionId)
                .then()
                .block();
    }

    private static OidcLoginMutator organiser() {
        return mockOidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ORGANISER"));
    }

    private static OidcLoginMutator standardUser() {
        return mockOidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    /**
     * Unlike {@link #standardUser()} (role-only, no real principal), routes using
     * {@code @AuthenticationPrincipal HackathonOidcUser} — e.g. {@code GET /register} — need a
     * genuine {@link net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser} principal
     * wrapping a persisted {@link User}, the same pattern {@code RegistrationManagementIT} uses.
     */
    private static OidcLoginMutator loginAs(User user) {
        java.time.Instant issuedAt = java.time.Instant.now();
        org.springframework.security.oauth2.core.oidc.OidcIdToken idToken =
                org.springframework.security.oauth2.core.oidc.OidcIdToken.withTokenValue("token-value")
                        .subject(user.getOidcSubject())
                        .issuedAt(issuedAt)
                        .expiresAt(issuedAt.plusSeconds(300))
                        .claim("name", user.getDisplayName())
                        .build();
        org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser delegate =
                new org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser(
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken);
        net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser principal =
                new net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser(user, delegate);
        return mockOidcLogin().oidcUser(principal);
    }

    private User persistStandardUser() {
        User user = new User();
        user.setOidcSubject("sub-" + UUID.randomUUID());
        user.setDisplayName("User " + UUID.randomUUID());
        user.setOrganiser(false);
        Instant now = Instant.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return userRepository.save(user).block();
    }
}

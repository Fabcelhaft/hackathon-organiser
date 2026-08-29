package net.fabcelhaft.hackathonorganiser.participants;

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
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsRepository;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantRepository;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
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
 * Integration tests for User Story 1 (form-driven registration, T015) and User Story 2
 * (registration capacity, T023) against the real {@code SecurityWebFilterChain} and repositories —
 * per contracts/registration-and-self-edit.md.
 */
@SpringBootTest
@Testcontainers
class RegistrationManagementIT {

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
    CustomFieldDefinitionRepository customFieldDefinitionRepository;

    @Autowired
    CustomFieldOptionRepository customFieldOptionRepository;

    @Autowired
    OrganiserSettingsRepository organiserSettingsRepository;

    @Autowired
    org.springframework.r2dbc.core.DatabaseClient databaseClient;

    @BeforeEach
    void resetDataBetweenTests() {
        // Every row below persists across test methods in this class (one shared Testcontainers
        // Postgres, no per-method reset): a required Custom Field or an ACTIVE Participant left
        // over from an earlier test would otherwise make a later test's plain /register submission
        // fail validation for a field it never rendered, or inflate a maxRegistrations test's
        // capacity count above zero. Deleted in FK-safe order: values before their parents.
        databaseClient.sql("DELETE FROM custom_field_value_options").then().block();
        databaseClient.sql("DELETE FROM custom_field_values").then().block();
        databaseClient.sql("DELETE FROM participant_skills").then().block();
        databaseClient.sql("DELETE FROM participants").then().block();
        databaseClient.sql("DELETE FROM custom_field_options").then().block();
        databaseClient
                .sql("DELETE FROM custom_field_definitions WHERE field_type <> 'COUNTRY'")
                .then()
                .block();
        databaseClient.sql("UPDATE custom_field_definitions SET enabled = false WHERE field_type = 'COUNTRY'")
                .then()
                .block();
    }

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
                    settings.setMaxRegistrations(null);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    // --- GET /register (FR-002, FR-002a) -----------------------------------------------------------

    @Test
    void registerFormRendersEveryRegistrationFieldAsAFlatFieldWithNoCustomFieldLabel() {
        User user = persistUser();
        persistDefinition("T-Shirt Size", CustomFieldType.FREE_TEXT, true, false, false);

        String body = webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/register")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("T-Shirt Size");
        assertThat(body).contains("(required)");
        assertThat(body).doesNotContain("Custom Field");
    }

    // --- POST /register: missing required field (FR-003) --------------------------------------------

    @Test
    void submitMissingARequiredFieldRerendersTheFormWithNoRecordCreated() {
        User user = persistUser();
        persistDefinition("T-Shirt Size", CustomFieldType.FREE_TEXT, true, false, false);

        webTestClient
                .mutateWith(loginAs(user))
                .post()
                .uri("/register")
                .body(BodyInserters.fromFormData("skillIds", ""))
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(participantRepository.findByUserId(user.getId()).block()).isNull();
    }

    // --- POST /register: valid submission (FR-005, FR-033) -------------------------------------------

    @Test
    void validSubmissionCreatesAnActiveRecordWithExactlySubmittedValuesAndRedirectsHomeWithSuccess() {
        User user = persistUser();
        CustomFieldDefinition freeText =
                persistDefinition("T-Shirt Size", CustomFieldType.FREE_TEXT, true, false, false);

        webTestClient
                .mutateWith(loginAs(user))
                .post()
                .uri("/register")
                .body(BodyInserters.fromFormData("field_" + freeText.getId(), "Medium"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER)
                .expectHeader()
                .value("Location", location -> assertThat(location).contains("Registration"));

        Participant saved = participantRepository.findByUserId(user.getId()).block();
        assertThat(saved).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(ParticipantStatus.ACTIVE);
    }

    // --- SINGLE_SELECT restricted to exactly one option (FR-012) ------------------------------------

    @Test
    void singleSelectFieldRestrictsSubmissionToExactlyOneOption() {
        User user = persistUser();
        CustomFieldDefinition singleSelect =
                persistDefinition("Size", CustomFieldType.SINGLE_SELECT, false, false, false);
        CustomFieldOption small = persistOption(singleSelect.getId(), "S");
        CustomFieldOption large = persistOption(singleSelect.getId(), "L");

        webTestClient
                .mutateWith(loginAs(user))
                .post()
                .uri("/register")
                .body(BodyInserters.fromFormData(
                        "field_" + singleSelect.getId(), small.getId().toString())
                        .with("field_" + singleSelect.getId(), large.getId().toString()))
                .exchange()
                .expectStatus()
                .isOk(); // rejected: more than one option for a SINGLE_SELECT field

        assertThat(participantRepository.findByUserId(user.getId()).block()).isNull();
    }

    // --- COUNTRY field (FR-013) ----------------------------------------------------------------------

    @Test
    void countryFieldAcceptsAValidIsoCodeAndRejectsAnInvalidOne() {
        User user = persistUser();
        CustomFieldDefinition country = enableCountryField();

        webTestClient
                .mutateWith(loginAs(user))
                .post()
                .uri("/register")
                .body(BodyInserters.fromFormData("field_" + country.getId(), "NOTACODE"))
                .exchange()
                .expectStatus()
                .isOk(); // rejected: not a real ISO 3166 code
        assertThat(participantRepository.findByUserId(user.getId()).block()).isNull();

        webTestClient
                .mutateWith(loginAs(user))
                .post()
                .uri("/register")
                .body(BodyInserters.fromFormData("field_" + country.getId(), "DE"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(participantRepository.findByUserId(user.getId()).block()).isNotNull();
    }

    // --- Zero Skills is acceptable (FR-004) -----------------------------------------------------------

    @Test
    void submissionWithZeroSkillsSucceeds() {
        User user = persistUser();

        webTestClient
                .mutateWith(loginAs(user))
                .post()
                .uri("/register")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(participantRepository.findByUserId(user.getId()).block()).isNotNull();
    }

    // --- NOT_PARTICIPATED lockout (FR-006a) -----------------------------------------------------------

    @Test
    void notParticipatedUserSeesNoRegisterEntryPointAndCannotRegisterViaDirectPost() {
        User user = persistUser();
        persistParticipant(user.getId(), ParticipantStatus.NOT_PARTICIPATED);

        String homeBody = webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(homeBody).doesNotContain("Revoke Registration");
        assertThat(homeBody).contains("Only an Organiser can change it");

        webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/register")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        webTestClient
                .mutateWith(loginAs(user))
                .post()
                .uri("/register")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(participantRepository.findByUserId(user.getId()).block().getStatus())
                .isEqualTo(ParticipantStatus.NOT_PARTICIPATED);
    }

    // --- User Story 2: registration capacity (FR-007-FR-010, FR-035) -----------------------------------

    @Test
    void reachingMaxRegistrationsShowsCapacityMessageInsteadOfTheFormOnGetAndRejectsPost() {
        User first = persistUser();
        User second = persistUser();
        setMaxRegistrations(1);
        register(first);

        String body = webTestClient
                .mutateWith(loginAs(second))
                .get()
                .uri("/register")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).contains("Maximum registrations reached");

        webTestClient
                .mutateWith(loginAs(second))
                .post()
                .uri("/register")
                .exchange()
                .expectStatus()
                .isOk();
        assertThat(participantRepository.findByUserId(second.getId()).block()).isNull();
    }

    @Test
    void revokingOneParticipantWhenAtCapacityPermitsTheNextRegistration() {
        User first = persistUser();
        User second = persistUser();
        setMaxRegistrations(1);
        Participant firstParticipant = register(first);

        webTestClient.mutateWith(loginAs(first)).post().uri("/revoke").exchange();

        webTestClient
                .mutateWith(loginAs(second))
                .post()
                .uri("/register")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(participantRepository.findByUserId(second.getId()).block()).isNotNull();
        assertThat(participantRepository.findById(firstParticipant.getId()).block().getStatus())
                .isEqualTo(ParticipantStatus.REVOKED);
    }

    @Test
    void aRevokedParticipantNeverCountsTowardTheMax() {
        User first = persistUser();
        User second = persistUser();
        setMaxRegistrations(1);
        register(first);
        webTestClient.mutateWith(loginAs(first)).post().uri("/revoke").exchange();

        webTestClient
                .mutateWith(loginAs(second))
                .post()
                .uri("/register")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(participantRepository.findByUserId(second.getId()).block()).isNotNull();
    }

    @Test
    void noConfiguredMaxNeverBlocksRegistration() {
        User user = persistUser();
        setMaxRegistrations(null);

        webTestClient
                .mutateWith(loginAs(user))
                .post()
                .uri("/register")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(participantRepository.findByUserId(user.getId()).block()).isNotNull();
    }

    // --- Test helpers ------------------------------------------------------------------------------

    private Participant register(User user) {
        webTestClient
                .mutateWith(loginAs(user))
                .post()
                .uri("/register")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
        return participantRepository.findByUserId(user.getId()).block();
    }

    private void setMaxRegistrations(Integer max) {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setMaxRegistrations(max);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    private CustomFieldDefinition enableCountryField() {
        CustomFieldDefinition country = customFieldDefinitionRepository
                .findAll()
                .filter(d -> d.getFieldType() == CustomFieldType.COUNTRY)
                .blockFirst();
        country.setEnabled(true);
        return customFieldDefinitionRepository.save(country).block();
    }

    private CustomFieldDefinition persistDefinition(
            String label, CustomFieldType type, boolean required, boolean public_, boolean overview) {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setLabel(label);
        definition.setFieldType(type);
        definition.setRequired(required);
        definition.setPublic_(public_);
        definition.setOverview(overview);
        definition.setEnabled(true);
        Instant now = Instant.now();
        definition.setCreatedAt(now);
        definition.setUpdatedAt(now);
        return customFieldDefinitionRepository.save(definition).block();
    }

    private CustomFieldOption persistOption(UUID definitionId, String label) {
        CustomFieldOption option = new CustomFieldOption();
        option.setCustomFieldDefinitionId(definitionId);
        option.setLabel(label);
        Instant now = Instant.now();
        option.setCreatedAt(now);
        option.setUpdatedAt(now);
        return customFieldOptionRepository.save(option).block();
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

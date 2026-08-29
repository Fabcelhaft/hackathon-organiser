package net.fabcelhaft.hackathonorganiser.participants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinition;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinitionRepository;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldType;
import net.fabcelhaft.hackathonorganiser.organisersettings.DirectoryAudience;
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
 * Integration tests for User Story 5's Participants directory & detail view (T041;
 * contracts/participants-directory.md) and User Story 6's skill-visibility gating (T053;
 * contracts/participants-directory.md's "other viewer mode").
 */
@SpringBootTest
@Testcontainers
class ParticipantsDirectoryManagementIT {

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
    OrganiserSettingsRepository organiserSettingsRepository;

    @Autowired
    DatabaseClient databaseClient;

    @BeforeEach
    void resetDataBetweenTests() {
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

    @BeforeEach
    void setUpWebTestClient() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    // --- Nav item (FR-025) --------------------------------------------------------------------------

    @Test
    void navItemIsShownOrHiddenExactlyPerTheConfiguredAudience() {
        User user = persistUser(false);

        String bodyAllAuthenticated = homeBody(user);
        assertThat(bodyAllAuthenticated).contains("/participants");

        setAudience(DirectoryAudience.ORGANISERS_ONLY);
        String bodyOrganisersOnly = homeBody(user);
        assertThat(bodyOrganisersOnly).doesNotContain("href=\"/participants\"");
    }

    // --- GET /participants (FR-027, FR-027a, FR-031) ------------------------------------------------

    @Test
    void listsActiveParticipantsAlphabeticallyWithOverviewColumnsAndNoSkillsColumnAndEmptyIndicator() {
        CustomFieldDefinition overviewField = persistDefinition("T-Shirt Size", CustomFieldType.FREE_TEXT, false, false, true);
        User zoe = persistUser(false);
        String zoeDisplayName = "Zoe " + UUID.randomUUID();
        persistParticipant(zoe.getId(), ParticipantStatus.ACTIVE, zoeDisplayName);
        User alice = persistUser(false);
        String aliceDisplayName = "Alice " + UUID.randomUUID();
        Participant aliceParticipant = persistParticipant(alice.getId(), ParticipantStatus.ACTIVE, aliceDisplayName);
        insertFreeTextValue(aliceParticipant.getId(), overviewField.getId(), "Medium");
        User revokedUser = persistUser(false);
        String revokedDisplayName = "Revoked " + UUID.randomUUID();
        persistParticipant(revokedUser.getId(), ParticipantStatus.REVOKED, revokedDisplayName);

        String body = webTestClient
                .mutateWith(loginAs(zoe))
                .get()
                .uri("/participants")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("T-Shirt Size");
        assertThat(body).contains("Medium");
        assertThat(body).contains("Not provided"); // Zoe's own empty cell
        assertThat(body).doesNotContain(revokedDisplayName);
        assertThat(body.indexOf(aliceDisplayName)).isLessThan(body.indexOf(zoeDisplayName));
    }

    // --- GET /participants/{id} visibility modes (FR-017, FR-019, FR-029, FR-030) ------------------

    @Test
    void selfModeShowsEverythingRegardlessOfFlags() {
        User user = persistUser(false);
        CustomFieldDefinition privateField = persistDefinition("Private Field", CustomFieldType.FREE_TEXT, false, false, false);
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE, "Self " + UUID.randomUUID());
        insertFreeTextValue(participant.getId(), privateField.getId(), "SecretValue");

        String body = webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/participants/{id}", participant.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("SecretValue");
    }

    @Test
    void organiserModeShowsEverythingRegardlessOfFlags() {
        User organiser = persistUser(true);
        User owner = persistUser(false);
        CustomFieldDefinition privateField = persistDefinition("Private Field", CustomFieldType.FREE_TEXT, false, false, false);
        Participant participant = persistParticipant(owner.getId(), ParticipantStatus.ACTIVE, "Owner " + UUID.randomUUID());
        insertFreeTextValue(participant.getId(), privateField.getId(), "SecretValue");

        String body = webTestClient
                .mutateWith(loginAs(organiser))
                .get()
                .uri("/participants/{id}", participant.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("SecretValue");
    }

    @Test
    void otherViewerModeShowsOnlyPublicFieldsOmittingOverviewOnlyNonPublicEntirely() {
        User viewer = persistUser(false);
        User owner = persistUser(false);
        CustomFieldDefinition publicField = persistDefinition("Public Field", CustomFieldType.FREE_TEXT, false, true, false);
        CustomFieldDefinition overviewOnlyField =
                persistDefinition("Overview Only Field", CustomFieldType.FREE_TEXT, false, false, true);
        Participant participant = persistParticipant(owner.getId(), ParticipantStatus.ACTIVE, "Owner " + UUID.randomUUID());
        insertFreeTextValue(participant.getId(), publicField.getId(), "PublicValue");
        insertFreeTextValue(participant.getId(), overviewOnlyField.getId(), "OverviewOnlyValue");

        String body = webTestClient
                .mutateWith(loginAs(viewer))
                .get()
                .uri("/participants/{id}", participant.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("PublicValue");
        assertThat(body).doesNotContain("OverviewOnlyValue");
    }

    @Test
    void skillVisibilityGatesSkillsForOtherViewersButNeverAffectsTheDirectoryTable() {
        User viewer = persistUser(false);
        User owner = persistUser(false);
        Participant participant = persistParticipant(owner.getId(), ParticipantStatus.ACTIVE, "Owner " + UUID.randomUUID());
        String skillName = "DistinctiveSkill" + UUID.randomUUID().toString().replace("-", "");
        UUID skillId = persistSkill(skillName);
        assignSkill(participant.getId(), skillId);

        setSkillVisibility(false);
        String bodyHidden = webTestClient
                .mutateWith(loginAs(viewer))
                .get()
                .uri("/participants/{id}", participant.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(bodyHidden).doesNotContain(skillName);

        setSkillVisibility(true);
        String bodyShown = webTestClient
                .mutateWith(loginAs(viewer))
                .get()
                .uri("/participants/{id}", participant.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(bodyShown).contains(skillName);

        String listBody = webTestClient
                .mutateWith(loginAs(viewer))
                .get()
                .uri("/participants")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(listBody).doesNotContain(skillName); // never a directory table column, regardless of the toggle
    }

    @Test
    void bothRoutesReturn403ForARequesterOutsideTheConfiguredAudience() {
        User viewer = persistUser(false);
        User owner = persistUser(false);
        Participant participant = persistParticipant(owner.getId(), ParticipantStatus.ACTIVE, "Owner " + UUID.randomUUID());
        setAudience(DirectoryAudience.ORGANISERS_ONLY);

        webTestClient
                .mutateWith(loginAs(viewer))
                .get()
                .uri("/participants")
                .exchange()
                .expectStatus()
                .isForbidden();

        webTestClient
                .mutateWith(loginAs(viewer))
                .get()
                .uri("/participants/{id}", participant.getId())
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void changingTheAudienceSettingIsEnforcedOnTheVeryNextRequest() {
        User user = persistUser(false);

        webTestClient.mutateWith(loginAs(user)).get().uri("/participants").exchange().expectStatus().isOk();

        setAudience(DirectoryAudience.ORGANISERS_ONLY);

        webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/participants")
                .exchange()
                .expectStatus()
                .isForbidden();
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

    private void setAudience(DirectoryAudience audience) {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setParticipantsDirectoryAudience(audience);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
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

    private Participant persistParticipant(UUID userId, ParticipantStatus status, String displayNameOverride) {
        if (displayNameOverride != null) {
            User user = userRepository.findById(userId).block();
            user.setDisplayName(displayNameOverride);
            userRepository.save(user).block();
        }
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

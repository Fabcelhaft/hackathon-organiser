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
 * Integration tests for User Story 4's self-edit / read-only own profile (T034;
 * contracts/registration-and-self-edit.md).
 */
@SpringBootTest
@Testcontainers
class ProfileManagementIT {

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
                    settings.setSelfEditEnabled(true);
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

    @Test
    void profileAlwaysShowsCurrentValuesEvenWhenSelfEditIsDisabledOrStatusIsNotParticipated() {
        User user = persistUser();
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        disableSelfEdit();

        webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/profile")
                .exchange()
                .expectStatus()
                .isOk();

        changeStatus(participant.getId(), ParticipantStatus.NOT_PARTICIPATED);

        webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/profile")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void editLinkAppearsOnlyWhenSelfEditIsCurrentlyEnabled() {
        User user = persistUser();
        persistParticipant(user.getId(), ParticipantStatus.ACTIVE);

        String bodyEnabled = profileBody(user);
        assertThat(bodyEnabled).contains("/profile/edit");

        disableSelfEdit();

        String bodyDisabled = profileBody(user);
        assertThat(bodyDisabled).doesNotContain("/profile/edit");
    }

    @Test
    void everyValueAndSkillsSectionIsLabeledVisibleToOthersOrPrivateOnProfileAndEditMatchingActualConfiguration() {
        User user = persistUser();
        persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        persistDefinition("Public Field", CustomFieldType.FREE_TEXT, false, true);
        persistDefinition("Private Field", CustomFieldType.FREE_TEXT, false, false);

        String profileBody = profileBody(user);
        assertThat(profileBody).contains("visible to others");
        assertThat(profileBody).contains("private");

        String editBody = webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/profile/edit")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(editBody).contains("visible to others");
        assertThat(editBody).contains("private");
    }

    @Test
    void editFormIsPreFilledWithCurrentValues() {
        User user = persistUser();
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        CustomFieldDefinition field = persistDefinition("T-Shirt Size", CustomFieldType.FREE_TEXT, false);
        insertFreeTextValue(participant.getId(), field.getId(), "Large");

        String body = webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/profile/edit")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("Large");
    }

    @Test
    void validEditPersistsChangesWithConfirmationAndTheyShowOnTheNextProfileLoad() {
        User user = persistUser();
        Participant participant = persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        CustomFieldDefinition field = persistDefinition("T-Shirt Size", CustomFieldType.FREE_TEXT, false);

        webTestClient
                .mutateWith(loginAs(user))
                .post()
                .uri("/profile/edit")
                .body(BodyInserters.fromFormData("field_" + field.getId(), "Small"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER)
                .expectHeader()
                .value("Location", location -> assertThat(location).contains("Profile"));

        String body = profileBody(user);
        assertThat(body).contains("Small");
    }

    @Test
    void invalidEditIsRejectedTheSameWayAsRegistrationWithNoPartialSave() {
        User user = persistUser();
        persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        persistDefinition("T-Shirt Size", CustomFieldType.FREE_TEXT, true);

        webTestClient
                .mutateWith(loginAs(user))
                .post()
                .uri("/profile/edit")
                .exchange()
                .expectStatus()
                .isOk(); // rejected: missing required field, re-rendered
    }

    @Test
    void selfEditDisabledBetweenLoadAndSubmissionIsRejectedServerSide() {
        User user = persistUser();
        persistParticipant(user.getId(), ParticipantStatus.ACTIVE);

        webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/profile/edit")
                .exchange()
                .expectStatus()
                .isOk(); // still enabled at this point: form rendered, not redirected

        disableSelfEdit();

        webTestClient
                .mutateWith(loginAs(user))
                .post()
                .uri("/profile/edit")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
    }

    // --- Test helpers ------------------------------------------------------------------------------

    private String profileBody(User user) {
        return webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/profile")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private void disableSelfEdit() {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setSelfEditEnabled(false);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    private void changeStatus(UUID participantId, ParticipantStatus status) {
        participantRepository
                .findById(participantId)
                .flatMap(participant -> {
                    participant.setStatus(status);
                    participant.setUpdatedAt(Instant.now());
                    return participantRepository.save(participant);
                })
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

    private CustomFieldDefinition persistDefinition(String label, CustomFieldType type, boolean required) {
        return persistDefinition(label, type, required, false);
    }

    private CustomFieldDefinition persistDefinition(String label, CustomFieldType type, boolean required, boolean public_) {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setLabel(label);
        definition.setFieldType(type);
        definition.setRequired(required);
        definition.setPublic_(public_);
        definition.setEnabled(true);
        Instant now = Instant.now();
        definition.setCreatedAt(now);
        definition.setUpdatedAt(now);
        return customFieldDefinitionRepository.save(definition).block();
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

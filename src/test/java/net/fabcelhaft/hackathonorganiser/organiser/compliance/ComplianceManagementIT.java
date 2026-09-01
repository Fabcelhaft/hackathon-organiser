package net.fabcelhaft.hackathonorganiser.organiser.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceDiversityRequirementRepository;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinition;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinitionRepository;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldType;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsRepository;
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
 * Integration tests for User Story 6's Compliance Ruleset management (T049;
 * contracts/compliance-settings-and-override.md): a fresh instance's seeded default Maximum, valid
 * and invalid max/min saves, diversity-requirement add/remove, and Organiser-only access (SC-007).
 */
@SpringBootTest
@Testcontainers
class ComplianceManagementIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @Autowired
    ApplicationContext applicationContext;

    WebTestClient webTestClient;

    @Autowired
    UserRepository userRepository;

    @Autowired
    OrganiserSettingsRepository organiserSettingsRepository;

    @Autowired
    CustomFieldDefinitionRepository customFieldDefinitionRepository;

    @Autowired
    ComplianceDiversityRequirementRepository requirementRepository;

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
                    settings.setMaxGroupMembers(5);
                    settings.setMinGroupMembers(null);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    @BeforeEach
    void clearExistingDiversityRequirements() {
        requirementRepository.deleteAll().block();
    }

    @Test
    void freshInstanceShowsTheSeededDefaultMaximumNoMinimumAndAnEmptyRequirementList() {
        User organiser = persistUser(true);

        String body = webTestClient
                .mutateWith(loginAs(organiser))
                .get()
                .uri("/organiser/compliance")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("value=\"5\"");
        assertThat(body).contains("No diversity requirements configured.");
    }

    @Test
    void savingAValidMaxAndMinSucceeds() {
        User organiser = persistUser(true);

        webTestClient
                .mutateWith(loginAs(organiser))
                .post()
                .uri("/organiser/compliance")
                .body(BodyInserters.fromFormData("max_group_members", "3").with("min_group_members", "2"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        var settings = organiserSettingsRepository.findBySingletonTrue().block();
        assertThat(settings.getMaxGroupMembers()).isEqualTo(3);
        assertThat(settings.getMinGroupMembers()).isEqualTo(2);
    }

    @Test
    void savingABlankMaximumIsRejected() {
        User organiser = persistUser(true);

        webTestClient
                .mutateWith(loginAs(organiser))
                .post()
                .uri("/organiser/compliance")
                .body(BodyInserters.fromFormData("max_group_members", ""))
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(organiserSettingsRepository.findBySingletonTrue().block().getMaxGroupMembers())
                .isEqualTo(5);
    }

    @Test
    void savingAMinimumAboveTheMaximumIsRejected() {
        User organiser = persistUser(true);

        webTestClient
                .mutateWith(loginAs(organiser))
                .post()
                .uri("/organiser/compliance")
                .body(BodyInserters.fromFormData("max_group_members", "3").with("min_group_members", "5"))
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(organiserSettingsRepository.findBySingletonTrue().block().getMinGroupMembers())
                .isNull();
    }

    @Test
    void addingAValidDiversityRequirementSucceeds() {
        User organiser = persistUser(true);
        CustomFieldDefinition field = persistDefinition("Country " + UUID.randomUUID());

        webTestClient
                .mutateWith(loginAs(organiser))
                .post()
                .uri("/organiser/compliance/diversity-requirements")
                .body(BodyInserters.fromFormData("custom_field_definition_id", field.getId().toString())
                        .with("minimum_distinct_values", "2"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(requirementRepository.existsByCustomFieldDefinitionId(field.getId()).block())
                .isTrue();
    }

    @Test
    void addingARequirementWithMinimumBelowTwoIsRejected() {
        User organiser = persistUser(true);
        CustomFieldDefinition field = persistDefinition("Language " + UUID.randomUUID());

        webTestClient
                .mutateWith(loginAs(organiser))
                .post()
                .uri("/organiser/compliance/diversity-requirements")
                .body(BodyInserters.fromFormData("custom_field_definition_id", field.getId().toString())
                        .with("minimum_distinct_values", "1"))
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(requirementRepository.existsByCustomFieldDefinitionId(field.getId()).block())
                .isFalse();
    }

    @Test
    void removingAnExistingRequirementSucceeds() {
        User organiser = persistUser(true);
        CustomFieldDefinition field = persistDefinition("Diet " + UUID.randomUUID());
        webTestClient
                .mutateWith(loginAs(organiser))
                .post()
                .uri("/organiser/compliance/diversity-requirements")
                .body(BodyInserters.fromFormData("custom_field_definition_id", field.getId().toString())
                        .with("minimum_distinct_values", "2"))
                .exchange();
        UUID requirementId = requirementRepository
                .findAll()
                .filter(r -> r.getCustomFieldDefinitionId().equals(field.getId()))
                .blockFirst()
                .getId();

        webTestClient
                .mutateWith(loginAs(organiser))
                .post()
                .uri("/organiser/compliance/diversity-requirements/{id}/delete", requirementId)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(requirementRepository.existsByCustomFieldDefinitionId(field.getId()).block())
                .isFalse();
    }

    @Test
    void everyRouteDeniesANonOrganiser() {
        User standardUser = persistUser(false);

        webTestClient
                .mutateWith(loginAs(standardUser))
                .get()
                .uri("/organiser/compliance")
                .exchange()
                .expectStatus()
                .isForbidden();

        webTestClient
                .mutateWith(loginAs(standardUser))
                .post()
                .uri("/organiser/compliance")
                .body(BodyInserters.fromFormData("max_group_members", "3"))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    // --- Test helpers ------------------------------------------------------------------------------

    private CustomFieldDefinition persistDefinition(String label) {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setLabel(label);
        definition.setFieldType(CustomFieldType.FREE_TEXT);
        definition.setRequired(false);
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

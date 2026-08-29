package net.fabcelhaft.hackathonorganiser.organiser.settings;

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
 * Integration tests for User Story 2's Organiser Settings management (T018;
 * contracts/organiser-settings.md): toggling self-registration/self-revocation changes what a
 * subsequent {@code /register}/{@code /revoke} allows on the very next request (FR-023, SC-003),
 * each toggle shows its current state plus a plain-language effect sentence (FR-005a), a
 * non-Organiser is denied every route (FR-005), and the shared layout's Organiser nav link is
 * visible only to {@code ROLE_ORGANISER} (FR-008).
 */
@SpringBootTest
@Testcontainers
class SettingsManagementIT {

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
    void organiserCanViewTheSettingsFormShowingCurrentStateAndEffectSentences() {
        User organiser = persistUser(true);

        String body = webTestClient
                .mutateWith(loginAs(organiser))
                .get()
                .uri("/organiser/settings")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("Users can register themselves as Active Participants");
        assertThat(body).contains("Users can revoke their own registration");
        assertThat(body).contains("for=\"self_registration_enabled\"");
        assertThat(body).contains("for=\"self_revocation_enabled\"");
    }

    @Test
    void disablingSelfRegistrationBlocksRegisterOnTheVeryNextRequestAndReenablingAllowsItAgain() {
        User organiser = persistUser(true);
        User standardUser = persistUser(false);

        updateSettings(organiser, false, true);

        webTestClient
                .mutateWith(loginAs(standardUser))
                .post()
                .uri("/register")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(participantRepository.findByUserId(standardUser.getId()).block()).isNull();

        updateSettings(organiser, true, true);

        webTestClient
                .mutateWith(loginAs(standardUser))
                .post()
                .uri("/register")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(participantRepository.findByUserId(standardUser.getId()).block()).isNotNull();
    }

    @Test
    void disablingSelfRevocationBlocksRevokeOnTheVeryNextRequestAndReenablingAllowsItAgain() {
        User organiser = persistUser(true);
        User standardUser = persistUser(false);
        Participant participant = persistParticipant(standardUser.getId());

        updateSettings(organiser, true, false);

        webTestClient
                .mutateWith(loginAs(standardUser))
                .post()
                .uri("/revoke")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(participantRepository.findById(participant.getId()).block().getStatus())
                .isEqualTo(ParticipantStatus.ACTIVE);

        updateSettings(organiser, true, true);

        webTestClient
                .mutateWith(loginAs(standardUser))
                .post()
                .uri("/revoke")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(participantRepository.findById(participant.getId()).block().getStatus())
                .isEqualTo(ParticipantStatus.REVOKED);
    }

    @Test
    void nonOrganiserIsDeniedEverySettingsRoute() {
        User standardUser = persistUser(false);

        webTestClient
                .mutateWith(loginAs(standardUser))
                .get()
                .uri("/organiser/settings")
                .exchange()
                .expectStatus()
                .isForbidden();

        webTestClient
                .mutateWith(loginAs(standardUser))
                .post()
                .uri("/organiser/settings")
                .body(BodyInserters.fromFormData("self_registration_enabled", "true"))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void organiserNavLinkIsVisibleOnlyToAnOrganiser() {
        User organiser = persistUser(true);
        User standardUser = persistUser(false);

        String organiserHome = webTestClient
                .mutateWith(loginAs(organiser))
                .get()
                .uri("/")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(organiserHome).contains("/organiser/topics");

        String standardHome = webTestClient
                .mutateWith(loginAs(standardUser))
                .get()
                .uri("/")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(standardHome).doesNotContain("/organiser/topics");
    }

    // --- Test helpers ------------------------------------------------------------------------------

    private void updateSettings(User organiser, boolean selfRegistrationEnabled, boolean selfRevocationEnabled) {
        webTestClient
                .mutateWith(loginAs(organiser))
                .post()
                .uri("/organiser/settings")
                .body(BodyInserters.fromFormData(
                                "self_registration_enabled", String.valueOf(selfRegistrationEnabled))
                        .with("self_revocation_enabled", String.valueOf(selfRevocationEnabled)))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
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

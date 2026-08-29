package net.fabcelhaft.hackathonorganiser.organiser.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUserService;
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
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration tests for User Story 1 (T007), covering
 * specs/002-core-domain-model/contracts/user-management.md end to end against the real
 * {@code SecurityWebFilterChain}, {@code HackathonOidcUserService}, and {@code UserRepository} —
 * no hand-mocked security substitute.
 *
 * <p>Per research.md §6, a full browser OIDC handshake cannot be exercised without a live IdP.
 * First-login auto-provisioning (SC-001, SC-006) is therefore proven by invoking the real,
 * context-managed {@link HackathonOidcUserService} directly with a hand-built
 * {@link OidcUserRequest} — exactly the request shape the real
 * {@code OAuth2LoginAuthenticationWebFilter} would hand it after a successful code exchange — and
 * then asserting the persisted row through the real {@link UserRepository}. Route-level
 * authorization (list/view/toggle, non-Organiser denial) is proven with
 * {@code spring-security-test}'s reactive {@code mockOidcLogin()} against the real security
 * filter chain.
 */
// webEnvironment defaults to MOCK (not RANDOM_PORT, unlike ActuatorHealthIT): spring-security-test's
// reactive mockOidcLogin()/mockUser() support (research.md §6) requires WebTestClient bound to the
// ApplicationContext via WebTestClient.bindToApplicationContext(...).apply(springSecurity()) (the
// canonical pattern from the Spring Security reference docs, wired up in the @BeforeEach below) so
// it can splice a mocked SecurityContext into the real SecurityWebFilterChain; against a real
// socket-bound server (RANDOM_PORT) it fails fast with "Cannot apply Spring Security Test Support
// to null WebHttpHandlerBuilder". The SecurityWebFilterChain itself, and every bean it wires
// (HackathonOidcUserService, UserRepository), are still the real, fully-autoconfigured production
// beans either way.
@SpringBootTest
@Testcontainers
class UserManagementIT {

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
    HackathonOidcUserService hackathonOidcUserService;

    @Autowired
    ReactiveClientRegistrationRepository clientRegistrationRepository;

    // --- First-login auto-provisioning (SC-001, SC-006) -------------------------------------

    @Test
    void firstLoginAutoProvisionsStandardUserWithUuidV7Id() {
        String subject = "sub-" + UUID.randomUUID();

        OidcUser result = hackathonOidcUserService
                .loadUser(requestFor(subject, "Jane Doe", "jane@example.com"))
                .block();

        assertThat(result).isNotNull();
        assertThat(result.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_USER")
                .doesNotContain("ROLE_ORGANISER");

        User saved = userRepository.findByOidcSubject(subject).block();
        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getId().version()).isEqualTo(7); // UUIDv7, DB-assigned (research.md §1)
        assertThat(saved.isOrganiser()).isFalse();
        assertThat(saved.getDisplayName()).isEqualTo("Jane Doe");
        assertThat(saved.getEmail()).isEqualTo("jane@example.com");
    }

    @Test
    void returningLoginRefreshesProfileWithoutDuplicatingTheUser() {
        String subject = "sub-" + UUID.randomUUID();

        hackathonOidcUserService.loadUser(requestFor(subject, "Old Name", "old@example.com")).block();
        UUID firstId = userRepository.findByOidcSubject(subject).block().getId();

        hackathonOidcUserService.loadUser(requestFor(subject, "New Name", "new@example.com")).block();

        User updated = userRepository.findByOidcSubject(subject).block();
        assertThat(updated.getId()).isEqualTo(firstId); // same row, matched on sub (not profile fields)
        assertThat(updated.getDisplayName()).isEqualTo("New Name");
        assertThat(updated.getEmail()).isEqualTo("new@example.com");
    }

    // --- Listing / viewing users --------------------------------------------------------------

    @Test
    void organiserCanListAndViewUsers() {
        User user = persistUser("sub-" + UUID.randomUUID(), "List Target", "target@example.com", false);

        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/users")
                .exchange()
                .expectStatus().isOk();

        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/users/{id}", user.getId())
                .exchange()
                .expectStatus().isOk();

        webTestClient.mutateWith(organiser())
                .get().uri("/organiser/users/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- Granting / revoking the Organiser privilege (Acceptance Scenarios 2 & 3) -------------

    @Test
    void organiserCanGrantAndRevokeTheOrganiserPrivilege() {
        User user = persistUser("sub-" + UUID.randomUUID(), "Toggle Target", "toggle@example.com", false);

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/users/{id}/organiser", user.getId())
                .body(BodyInserters.fromFormData("organiser", "true"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER)
                .expectHeader().valueEquals("Location", "/organiser/users/" + user.getId());

        assertThat(userRepository.findById(user.getId()).block().isOrganiser()).isTrue();

        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/users/{id}/organiser", user.getId())
                .body(BodyInserters.fromFormData("organiser", "false"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(userRepository.findById(user.getId()).block().isOrganiser()).isFalse();
    }

    @Test
    void togglingUnknownUserReturnsNotFound() {
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/users/{id}/organiser", UUID.randomUUID())
                .body(BodyInserters.fromFormData("organiser", "true"))
                .exchange()
                .expectStatus().isNotFound();
    }

    // --- Revocation denies the NEXT access check (edge case in spec.md) -----------------------

    @Test
    void revokedPrivilegeDeniesTheNextAccessCheck() {
        String subject = "sub-" + UUID.randomUUID();
        User user = persistUser(subject, "Session User", "session@example.com", true);

        // Fresh derivation while still an Organiser: ROLE_ORGANISER is granted.
        OidcUser beforeRevoke = hackathonOidcUserService.loadUser(requestFor(subject, "Session User", "session@example.com")).block();
        assertThat(beforeRevoke.getAuthorities()).extracting(GrantedAuthority::getAuthority).contains("ROLE_ORGANISER");

        // An Organiser revokes the privilege via the contract's POST route.
        webTestClient.mutateWith(organiser())
                .post().uri("/organiser/users/{id}/organiser", user.getId())
                .body(BodyInserters.fromFormData("organiser", "false"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SEE_OTHER);

        // The NEXT access-check derivation for that same user no longer grants ROLE_ORGANISER.
        OidcUser afterRevoke = hackathonOidcUserService.loadUser(requestFor(subject, "Session User", "session@example.com")).block();
        assertThat(afterRevoke.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_USER")
                .doesNotContain("ROLE_ORGANISER");

        // And with that freshly-derived (now non-Organiser) authority set, /organiser/** denies.
        webTestClient.mutateWith(mockOidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .get().uri("/organiser/users")
                .exchange()
                .expectStatus().isForbidden();
    }

    // --- Non-Organiser denied on every route (FR-022, SC-004) ----------------------------------

    @Test
    void nonOrganiserIsDeniedOnEveryRoute() {
        User user = persistUser("sub-" + UUID.randomUUID(), "Standard User", "standard@example.com", false);

        webTestClient.mutateWith(standardUser())
                .get().uri("/organiser/users")
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .get().uri("/organiser/users/{id}", user.getId())
                .exchange()
                .expectStatus().isForbidden();

        webTestClient.mutateWith(standardUser())
                .post().uri("/organiser/users/{id}/organiser", user.getId())
                .body(BodyInserters.fromFormData("organiser", "true"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void unauthenticatedRequestIsDenied() {
        webTestClient.get().uri("/organiser/users")
                .exchange()
                .expectStatus().is3xxRedirection();
    }

    // --- Test helpers ---------------------------------------------------------------------------

    private User persistUser(String subject, String displayName, String email, boolean organiser) {
        User user = new User();
        user.setOidcSubject(subject);
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setOrganiser(organiser);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).block();
    }

    private OidcUserRequest requestFor(String subject, String name, String email) {
        ClientRegistration clientRegistration = clientRegistrationRepository.findByRegistrationId("oidc").block();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "access-token-value", Instant.now(), Instant.now().plusSeconds(300));
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token-value")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("name", name)
                .claim("email", email)
                .build();
        return new OidcUserRequest(clientRegistration, accessToken, idToken);
    }

    private static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.OidcLoginMutator organiser() {
        return mockOidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ORGANISER"));
    }

    private static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.OidcLoginMutator standardUser() {
        return mockOidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}

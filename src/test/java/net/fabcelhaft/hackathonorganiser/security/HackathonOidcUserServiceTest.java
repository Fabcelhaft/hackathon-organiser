package net.fabcelhaft.hackathonorganiser.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit test for {@link HackathonOidcUserService}'s OIDC user-upsert logic (T008).
 *
 * <p>The upstream {@link OidcReactiveOAuth2UserService}-shaped delegate is mocked so this test
 * exercises only the upsert logic against a mocked {@link UserRepository} — the real delegate and
 * real repository are covered by the Testcontainers-backed
 * {@code organiser.user.UserManagementIT}. Per Constitution Development Workflow #4, the
 * multi-operator reactive chain (lookup by subject -> conditional create/update -> save -> wrap)
 * is verified with {@link StepVerifier}, never {@code .block()}.
 */
@ExtendWith(MockitoExtension.class)
class HackathonOidcUserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ReactiveOAuth2UserService<OidcUserRequest, OidcUser> delegate;

    @Test
    void createsUserOnFirstLoginKeyedBySubject() {
        HackathonOidcUserService service = new HackathonOidcUserService(userRepository, delegate);
        OidcUserRequest request = someRequest();
        OidcUser upstreamUser = oidcUser("sub-123", "Jane Doe", "jane@example.com");

        when(delegate.loadUser(request)).thenReturn(Mono.just(upstreamUser));
        when(userRepository.findByOidcSubject("sub-123")).thenReturn(Mono.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.loadUser(request))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(HackathonOidcUser.class);
                    User saved = ((HackathonOidcUser) result).getUser();
                    assertThat(saved.getId()).isNull(); // left null; the database assigns it via uuidv7()
                    assertThat(saved.getOidcSubject()).isEqualTo("sub-123");
                    assertThat(saved.getDisplayName()).isEqualTo("Jane Doe");
                    assertThat(saved.getEmail()).isEqualTo("jane@example.com");
                    assertThat(saved.isOrganiser()).isFalse();
                    assertThat(result.getAuthorities())
                            .extracting(GrantedAuthority::getAuthority)
                            .containsExactly("ROLE_USER");
                })
                .verifyComplete();
    }

    @Test
    void refreshesProfileOnSubsequentLoginWithoutCreatingDuplicateAndMatchesBySubjectNotProfile() {
        HackathonOidcUserService service = new HackathonOidcUserService(userRepository, delegate);
        OidcUserRequest request = someRequest();
        // Upstream now reports a changed display name/email for the same stable subject.
        OidcUser upstreamUser = oidcUser("sub-123", "Jane Renamed", "jane.new@example.com");

        UUID existingId = UUID.randomUUID();
        User existing = new User();
        existing.setId(existingId);
        existing.setOidcSubject("sub-123");
        existing.setDisplayName("Jane Doe");
        existing.setEmail("jane@example.com");
        existing.setOrganiser(true);
        existing.setCreatedAt(Instant.now().minusSeconds(3600));
        existing.setUpdatedAt(Instant.now().minusSeconds(3600));

        when(delegate.loadUser(request)).thenReturn(Mono.just(upstreamUser));
        when(userRepository.findByOidcSubject("sub-123")).thenReturn(Mono.just(existing));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.loadUser(request))
                .assertNext(result -> {
                    User saved = ((HackathonOidcUser) result).getUser();
                    // Matched on the stable subject, not on the (changed) profile fields.
                    assertThat(saved.getId()).isEqualTo(existingId);
                    assertThat(saved.getOidcSubject()).isEqualTo("sub-123");
                    // Profile fields refreshed from the latest login's claims.
                    assertThat(saved.getDisplayName()).isEqualTo("Jane Renamed");
                    assertThat(saved.getEmail()).isEqualTo("jane.new@example.com");
                    // The organiser flag is untouched by login (only toggled via organiser views).
                    assertThat(saved.isOrganiser()).isTrue();
                    assertThat(result.getAuthorities())
                            .extracting(GrantedAuthority::getAuthority)
                            .contains("ROLE_ORGANISER");
                })
                .verifyComplete();

        // No duplicate row is created for a returning user: save() is always called with the
        // already-persisted id, never with a fresh (null-id) User.
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.argThat(u -> u.getId() == null));
    }

    private static OidcUser oidcUser(String subject, String name, String email) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(300);
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token-value")
                .subject(subject)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("name", name)
                .claim("email", email)
                .build();
        return new DefaultOidcUser(AuthorityUtils.createAuthorityList("SCOPE_openid"), idToken);
    }

    private static OidcUserRequest someRequest() {
        ClientRegistration clientRegistration = ClientRegistration.withRegistrationId("oidc")
                .clientId("test-client")
                .clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("http://localhost/oidc/auth")
                .tokenUri("http://localhost/oidc/token")
                .userNameAttributeName("sub")
                .build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "access-token-value", Instant.now(), Instant.now().plusSeconds(300));
        OidcIdToken idToken = OidcIdToken.withTokenValue("request-id-token-value")
                .subject("placeholder")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return new OidcUserRequest(clientRegistration, accessToken, idToken);
    }
}

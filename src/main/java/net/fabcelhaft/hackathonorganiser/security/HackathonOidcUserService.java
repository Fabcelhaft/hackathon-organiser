package net.fabcelhaft.hackathonorganiser.security;

import java.time.Instant;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * {@link ReactiveOAuth2UserService} that upserts a {@link User} row keyed by the OIDC {@code sub}
 * claim on every successful login (T013; research.md §2): creates the row on first login (FR-002)
 * and refreshes {@code display_name}/{@code email} on every subsequent login (edge case in
 * spec.md: match on the stable subject, never on mutable profile fields). The result is wrapped in
 * {@link HackathonOidcUser}, which re-derives {@code ROLE_ORGANISER} from the just-upserted row
 * (FR-005) so that a revoked/granted privilege is reflected the next time this service runs.
 *
 * <p>Fully non-blocking (Constitution II): the upsert runs entirely through the reactive
 * {@link UserRepository}, composed into the same {@code Mono} chain as the delegate's own load.
 */
@Service
public class HackathonOidcUserService implements ReactiveOAuth2UserService<OidcUserRequest, OidcUser> {

    private final UserRepository userRepository;
    private final ReactiveOAuth2UserService<OidcUserRequest, OidcUser> delegate;

    @Autowired
    public HackathonOidcUserService(UserRepository userRepository) {
        this(userRepository, createDefaultDelegate());
    }

    /**
     * Package-private constructor used by {@code HackathonOidcUserServiceTest} to inject a mocked
     * delegate, so the upsert logic can be unit-tested without a network round-trip to an IdP.
     */
    HackathonOidcUserService(UserRepository userRepository, ReactiveOAuth2UserService<OidcUserRequest, OidcUser> delegate) {
        this.userRepository = userRepository;
        this.delegate = delegate;
    }

    private static OidcReactiveOAuth2UserService createDefaultDelegate() {
        OidcReactiveOAuth2UserService oidcUserService = new OidcReactiveOAuth2UserService();
        // display_name/email are sourced from the ID token's own claims (scope: openid,profile,
        // email); no UserInfo endpoint round-trip is required, which keeps this upsert path free
        // of any dependency on a reachable IdP beyond the token endpoint the OAuth2 code-exchange
        // filter already calls.
        oidcUserService.setRetrieveUserInfo(request -> false);
        return oidcUserService;
    }

    @Override
    public Mono<OidcUser> loadUser(OidcUserRequest userRequest) {
        return delegate.loadUser(userRequest)
                .flatMap(oidcUser -> upsert(oidcUser).map(user -> new HackathonOidcUser(user, oidcUser)));
    }

    private Mono<User> upsert(OidcUser oidcUser) {
        String subject = oidcUser.getSubject();
        String displayName = oidcUser.getFullName() != null ? oidcUser.getFullName() : subject;
        String email = oidcUser.getEmail();
        Instant now = Instant.now();

        return userRepository.findByOidcSubject(subject)
                .flatMap(existing -> {
                    existing.setDisplayName(displayName);
                    existing.setEmail(email);
                    existing.setUpdatedAt(now);
                    return userRepository.save(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    User user = new User();
                    user.setOidcSubject(subject);
                    user.setDisplayName(displayName);
                    user.setEmail(email);
                    user.setOrganiser(false);
                    user.setCreatedAt(now);
                    user.setUpdatedAt(now);
                    return userRepository.save(user);
                }));
    }
}

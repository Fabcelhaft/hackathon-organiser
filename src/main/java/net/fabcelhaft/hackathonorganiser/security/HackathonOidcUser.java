package net.fabcelhaft.hackathonorganiser.security;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.fabcelhaft.hackathonorganiser.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Wraps the persisted {@link User} together with the upstream {@link OidcUser} returned by the
 * IdP (T012). Claims/attributes/tokens are delegated to the upstream principal; authorities are
 * derived solely from the database-stored {@link User#isOrganiser()} flag (FR-005) — never from
 * IdP-issued claims — so that {@code ROLE_ORGANISER} always reflects the current database state
 * as of the most recent {@link HackathonOidcUserService} invocation.
 */
public class HackathonOidcUser implements OidcUser {

    private final User user;
    private final OidcUser delegate;
    private final Set<GrantedAuthority> authorities;

    public HackathonOidcUser(User user, OidcUser delegate) {
        this.user = user;
        this.delegate = delegate;
        Set<GrantedAuthority> derived = new LinkedHashSet<>();
        derived.add(new SimpleGrantedAuthority("ROLE_USER")); // FR-003: Standard is implicit for every login
        if (user.isOrganiser()) {
            derived.add(new SimpleGrantedAuthority("ROLE_ORGANISER"));
        }
        this.authorities = Set.copyOf(derived);
    }

    /**
     * The persisted domain record backing this principal — used by organiser-area code that needs
     * the User's id or other database fields, not just IdP claims.
     */
    public User getUser() {
        return user;
    }

    @Override
    public Map<String, Object> getClaims() {
        return delegate.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return delegate.getUserInfo();
    }

    @Override
    public OidcIdToken getIdToken() {
        return delegate.getIdToken();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return delegate.getAttributes();
    }

    @Override
    public Set<GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }
}

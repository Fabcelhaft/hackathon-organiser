package net.fabcelhaft.hackathonorganiser.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link CurrentUserModelAdvice} (T003): resolves {@code currentUser}/
 * {@code isOrganiser} from an authenticated {@link HackathonOidcUser} in the reactive {@code
 * SecurityContext}; {@code isOrganiser} is {@code false} for a non-Organiser and for an
 * unauthenticated exchange. Per Constitution Development Workflow #4, this reactive-chain test
 * (context lookup -> cast -> mutate model) is verified with {@link StepVerifier}, never
 * {@code .block()}.
 */
class CurrentUserModelAdviceTest {

    private final CurrentUserModelAdvice advice = new CurrentUserModelAdvice();

    @Test
    void resolvesCurrentUserAndIsOrganiserTrueForAnOrganiser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setDisplayName("Org Person");
        user.setOrganiser(true);
        HackathonOidcUser principal = mock(HackathonOidcUser.class);
        when(principal.getUser()).thenReturn(user);
        Model model = new ExtendedModelMap();

        StepVerifier.create(advice.populateCurrentUser(model)
                        .contextWrite(withPrincipal(principal)))
                .verifyComplete();

        assertThat(model.getAttribute("currentUser")).isEqualTo(principal);
        assertThat(model.getAttribute("isOrganiser")).isEqualTo(true);
    }

    @Test
    void isOrganiserIsFalseForANonOrganiser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setDisplayName("Standard Person");
        user.setOrganiser(false);
        HackathonOidcUser principal = mock(HackathonOidcUser.class);
        when(principal.getUser()).thenReturn(user);
        Model model = new ExtendedModelMap();

        StepVerifier.create(advice.populateCurrentUser(model)
                        .contextWrite(withPrincipal(principal)))
                .verifyComplete();

        assertThat(model.getAttribute("currentUser")).isEqualTo(principal);
        assertThat(model.getAttribute("isOrganiser")).isEqualTo(false);
    }

    @Test
    void isOrganiserIsFalseAndCurrentUserIsAbsentWhenUnauthenticated() {
        Model model = new ExtendedModelMap();

        StepVerifier.create(advice.populateCurrentUser(model)).verifyComplete();

        assertThat(model.containsAttribute("currentUser")).isFalse();
        assertThat(model.getAttribute("isOrganiser")).isEqualTo(false);
    }

    private static reactor.util.context.Context withPrincipal(HackathonOidcUser principal) {
        Authentication authentication = new TestingAuthenticationToken(principal, null);
        SecurityContext securityContext = new SecurityContextImpl(authentication);
        return ReactiveSecurityContextHolder.withSecurityContext(Mono.just(securityContext));
    }
}

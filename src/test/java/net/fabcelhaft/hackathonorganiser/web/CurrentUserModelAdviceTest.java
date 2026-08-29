package net.fabcelhaft.hackathonorganiser.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettings;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantService;
import net.fabcelhaft.hackathonorganiser.participants.ParticipantsDirectoryAccessPolicy;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
 * Unit tests for {@link CurrentUserModelAdvice} (T003; T048): resolves {@code currentUser}/
 * {@code isOrganiser}/{@code showParticipantsMenuItem} from an authenticated {@link
 * HackathonOidcUser} in the reactive {@code SecurityContext}; {@code isOrganiser} is {@code false}
 * for a non-Organiser and for an unauthenticated exchange. Per Constitution Development Workflow
 * #4, this reactive-chain test (context lookup -> cast -> mutate model) is verified with
 * {@link StepVerifier}, never {@code .block()}.
 */
@ExtendWith(MockitoExtension.class)
class CurrentUserModelAdviceTest {

    @Mock
    private OrganiserSettingsService organiserSettingsService;

    @Mock
    private ParticipantService participantService;

    @Mock
    private ParticipantsDirectoryAccessPolicy accessPolicy;

    private CurrentUserModelAdvice advice;

    @BeforeEach
    void setUp() {
        advice = new CurrentUserModelAdvice(organiserSettingsService, participantService, accessPolicy);
        lenient().when(organiserSettingsService.current()).thenReturn(Mono.just(new OrganiserSettings()));
        lenient().when(participantService.findByUserId(any(UUID.class))).thenReturn(Mono.empty());
        lenient().when(accessPolicy.isInAudience(any(), anyBoolean(), anyBoolean())).thenReturn(false);
    }

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
        assertThat(model.getAttribute("showParticipantsMenuItem")).isEqualTo(false);
    }

    @Test
    void showParticipantsMenuItemReflectsTheAccessPolicy() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setDisplayName("Standard Person");
        user.setOrganiser(false);
        HackathonOidcUser principal = mock(HackathonOidcUser.class);
        when(principal.getUser()).thenReturn(user);
        Participant participant = new Participant();
        when(participantService.findByUserId(user.getId())).thenReturn(Mono.just(participant));
        when(accessPolicy.isInAudience(any(OrganiserSettings.class), org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(true);
        Model model = new ExtendedModelMap();

        StepVerifier.create(advice.populateCurrentUser(model)
                        .contextWrite(withPrincipal(principal)))
                .verifyComplete();

        assertThat(model.getAttribute("showParticipantsMenuItem")).isEqualTo(true);
    }

    private static reactor.util.context.Context withPrincipal(HackathonOidcUser principal) {
        Authentication authentication = new TestingAuthenticationToken(principal, null);
        SecurityContext securityContext = new SecurityContextImpl(authentication);
        return ReactiveSecurityContextHolder.withSecurityContext(Mono.just(securityContext));
    }
}

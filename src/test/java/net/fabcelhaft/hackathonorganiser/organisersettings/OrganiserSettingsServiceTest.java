package net.fabcelhaft.hackathonorganiser.organisersettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link OrganiserSettingsService} (T002): reads the seeded singleton row via
 * {@code findBySingletonTrue()}; updates any combination of the three toggles. Per Constitution
 * Development Workflow #4, the multi-operator reactive chain under test (read -> mutate -> write)
 * is verified with {@link StepVerifier}, never {@code .block()}.
 */
@ExtendWith(MockitoExtension.class)
class OrganiserSettingsServiceTest {

    @Mock
    private OrganiserSettingsRepository organiserSettingsRepository;

    private OrganiserSettingsService organiserSettingsService;

    @BeforeEach
    void setUp() {
        organiserSettingsService = new OrganiserSettingsService(organiserSettingsRepository);
    }

    @Test
    void currentReadsTheSeededSingletonRow() {
        OrganiserSettings settings = settingsOf(true, true, false);
        when(organiserSettingsRepository.findBySingletonTrue()).thenReturn(Mono.just(settings));

        StepVerifier.create(organiserSettingsService.current())
                .expectNext(settings)
                .verifyComplete();
    }

    @Test
    void updateChangesOnlyTheGivenToggles() {
        OrganiserSettings settings = settingsOf(true, true, false);
        when(organiserSettingsRepository.findBySingletonTrue()).thenReturn(Mono.just(settings));
        when(organiserSettingsRepository.save(any(OrganiserSettings.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(organiserSettingsService.update(false, null, true, null, null, null, null, null, null, null, null))
                .assertNext(saved -> {
                    assertThat(saved.isSelfRegistrationEnabled()).isFalse();
                    assertThat(saved.isSelfRevocationEnabled()).isTrue();
                    assertThat(saved.isTopicApprovalRequired()).isTrue();
                    assertThat(saved.getUpdatedAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void updateWithAllNullsLeavesEveryBooleanAndEnumToggleUnchanged() {
        OrganiserSettings settings = settingsOf(true, false, true);
        settings.setMaxRegistrations(5);
        settings.setSelfEditEnabled(true);
        settings.setSkillVisibilityEnabled(false);
        settings.setParticipantsDirectoryAudience(DirectoryAudience.ORGANISERS_AND_PARTICIPANTS);
        when(organiserSettingsRepository.findBySingletonTrue()).thenReturn(Mono.just(settings));
        when(organiserSettingsRepository.save(any(OrganiserSettings.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(organiserSettingsService.update(null, null, null, 5, null, null, null, null, null, null, null))
                .assertNext(saved -> {
                    assertThat(saved.isSelfRegistrationEnabled()).isTrue();
                    assertThat(saved.isSelfRevocationEnabled()).isFalse();
                    assertThat(saved.isTopicApprovalRequired()).isTrue();
                    assertThat(saved.isSelfEditEnabled()).isTrue();
                    assertThat(saved.isSkillVisibilityEnabled()).isFalse();
                    assertThat(saved.getParticipantsDirectoryAudience())
                            .isEqualTo(DirectoryAudience.ORGANISERS_AND_PARTICIPANTS);
                })
                .verifyComplete();
    }

    // --- maxRegistrations (FR-007) ----------------------------------------------------------------

    @Test
    void updateWithANullMaxRegistrationsClearsAPreviouslySetLimit() {
        OrganiserSettings settings = settingsOf(true, true, false);
        settings.setMaxRegistrations(5);
        when(organiserSettingsRepository.findBySingletonTrue()).thenReturn(Mono.just(settings));
        when(organiserSettingsRepository.save(any(OrganiserSettings.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(organiserSettingsService.update(null, null, null, null, null, null, null, null, null, null, null))
                .assertNext(saved -> assertThat(saved.getMaxRegistrations()).isNull())
                .verifyComplete();
    }

    @Test
    void updateAcceptsAPositiveMaxRegistrations() {
        OrganiserSettings settings = settingsOf(true, true, false);
        when(organiserSettingsRepository.findBySingletonTrue()).thenReturn(Mono.just(settings));
        when(organiserSettingsRepository.save(any(OrganiserSettings.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(organiserSettingsService.update(null, null, null, 10, null, null, null, null, null, null, null))
                .assertNext(saved -> assertThat(saved.getMaxRegistrations()).isEqualTo(10))
                .verifyComplete();
    }

    @Test
    void updateRejectsZeroOrNegativeMaxRegistrationsAndAppliesNoChangeAtAll() {
        StepVerifier.create(organiserSettingsService.update(false, null, null, 0, null, null, null, null, null, null, null))
                .expectError(OrganiserSettingsConflictException.class)
                .verify();
        StepVerifier.create(organiserSettingsService.update(false, null, null, -1, null, null, null, null, null, null, null))
                .expectError(OrganiserSettingsConflictException.class)
                .verify();

        verify(organiserSettingsRepository, never()).findBySingletonTrue();
        verify(organiserSettingsRepository, never()).save(any());
    }

    // --- selfEditEnabled / skillVisibilityEnabled / participantsDirectoryAudience (FR-018, FR-021, FR-025) --

    @Test
    void updateSetsSelfEditSkillVisibilityAndDirectoryAudienceIndependently() {
        OrganiserSettings settings = settingsOf(true, true, false);
        settings.setSelfEditEnabled(true);
        settings.setSkillVisibilityEnabled(false);
        settings.setParticipantsDirectoryAudience(DirectoryAudience.ORGANISERS_ONLY);
        when(organiserSettingsRepository.findBySingletonTrue()).thenReturn(Mono.just(settings));
        when(organiserSettingsRepository.save(any(OrganiserSettings.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(organiserSettingsService.update(
                        null, null, null, null, false, true, DirectoryAudience.ALL_AUTHENTICATED,
                        null, null, null, null))
                .assertNext(saved -> {
                    assertThat(saved.isSelfEditEnabled()).isFalse();
                    assertThat(saved.isSkillVisibilityEnabled()).isTrue();
                    assertThat(saved.getParticipantsDirectoryAudience())
                            .isEqualTo(DirectoryAudience.ALL_AUTHENTICATED);
                })
                .verifyComplete();
    }

    // --- maxGroupMembers/minGroupMembers/topicJoiningEnabled/skillDisplayMode (FR-011, FR-011a, ---
    // --- FR-011b, FR-020a, FR-020d) ---------------------------------------------------------------

    @Test
    void updateWithANullMaxGroupMembersLeavesItUnchanged() {
        OrganiserSettings settings = settingsOf(true, true, false);
        settings.setMaxGroupMembers(5);
        when(organiserSettingsRepository.findBySingletonTrue()).thenReturn(Mono.just(settings));
        when(organiserSettingsRepository.save(any(OrganiserSettings.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(organiserSettingsService.update(
                        null, null, null, null, null, null, null, null, null, null, null))
                .assertNext(saved -> assertThat(saved.getMaxGroupMembers()).isEqualTo(5))
                .verifyComplete();
    }

    @Test
    void updateRejectsMaxGroupMembersBelowOneAndAppliesNoChangeAtAll() {
        StepVerifier.create(organiserSettingsService.update(
                        null, null, null, null, null, null, null, 0, null, null, null))
                .expectError(OrganiserSettingsConflictException.class)
                .verify();

        verify(organiserSettingsRepository, never()).findBySingletonTrue();
        verify(organiserSettingsRepository, never()).save(any());
    }

    @Test
    void updateAcceptsAValidMaxGroupMembers() {
        OrganiserSettings settings = settingsOf(true, true, false);
        settings.setMaxGroupMembers(5);
        when(organiserSettingsRepository.findBySingletonTrue()).thenReturn(Mono.just(settings));
        when(organiserSettingsRepository.save(any(OrganiserSettings.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(organiserSettingsService.update(
                        null, null, null, null, null, null, null, 3, null, null, null))
                .assertNext(saved -> assertThat(saved.getMaxGroupMembers()).isEqualTo(3))
                .verifyComplete();
    }

    @Test
    void updateWithANullMinGroupMembersClearsAPreviouslySetMinimum() {
        OrganiserSettings settings = settingsOf(true, true, false);
        settings.setMaxGroupMembers(5);
        settings.setMinGroupMembers(2);
        when(organiserSettingsRepository.findBySingletonTrue()).thenReturn(Mono.just(settings));
        when(organiserSettingsRepository.save(any(OrganiserSettings.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(organiserSettingsService.update(
                        null, null, null, null, null, null, null, null, null, null, null))
                .assertNext(saved -> assertThat(saved.getMinGroupMembers()).isNull())
                .verifyComplete();
    }

    @Test
    void updateRejectsAMinGroupMembersAboveTheCurrentMaximumAndAppliesNoChangeAtAll() {
        OrganiserSettings settings = settingsOf(true, true, false);
        settings.setMaxGroupMembers(5);
        when(organiserSettingsRepository.findBySingletonTrue()).thenReturn(Mono.just(settings));

        StepVerifier.create(organiserSettingsService.update(
                        null, null, null, null, null, null, null, null, 6, null, null))
                .expectError(OrganiserSettingsConflictException.class)
                .verify();

        verify(organiserSettingsRepository, never()).save(any());
    }

    @Test
    void updateRejectsAMinGroupMembersAboveANewlySubmittedMaximumInTheSameCall() {
        OrganiserSettings settings = settingsOf(true, true, false);
        settings.setMaxGroupMembers(5);
        when(organiserSettingsRepository.findBySingletonTrue()).thenReturn(Mono.just(settings));

        StepVerifier.create(organiserSettingsService.update(
                        null, null, null, null, null, null, null, 3, 4, null, null))
                .expectError(OrganiserSettingsConflictException.class)
                .verify();

        verify(organiserSettingsRepository, never()).save(any());
    }

    @Test
    void updateAcceptsAMinGroupMembersAtOrBelowTheEffectiveMaximum() {
        OrganiserSettings settings = settingsOf(true, true, false);
        settings.setMaxGroupMembers(5);
        when(organiserSettingsRepository.findBySingletonTrue()).thenReturn(Mono.just(settings));
        when(organiserSettingsRepository.save(any(OrganiserSettings.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(organiserSettingsService.update(
                        null, null, null, null, null, null, null, null, 2, null, null))
                .assertNext(saved -> assertThat(saved.getMinGroupMembers()).isEqualTo(2))
                .verifyComplete();
    }

    @Test
    void updateSetsTopicJoiningEnabledAndSkillDisplayModeIndependentlyAndLeavesThemUnchangedWhenNull() {
        OrganiserSettings settings = settingsOf(true, true, false);
        settings.setMaxGroupMembers(5);
        settings.setTopicJoiningEnabled(true);
        settings.setSkillDisplayMode(SkillDisplayMode.STILL_NEEDED_ONLY);
        when(organiserSettingsRepository.findBySingletonTrue()).thenReturn(Mono.just(settings));
        when(organiserSettingsRepository.save(any(OrganiserSettings.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(organiserSettingsService.update(
                        null, null, null, null, null, null, null,
                        null, null, false, SkillDisplayMode.ALL_ASSOCIATED))
                .assertNext(saved -> {
                    assertThat(saved.isTopicJoiningEnabled()).isFalse();
                    assertThat(saved.getSkillDisplayMode()).isEqualTo(SkillDisplayMode.ALL_ASSOCIATED);
                })
                .verifyComplete();

        StepVerifier.create(organiserSettingsService.update(
                        null, null, null, null, null, null, null, null, null, null, null))
                .assertNext(saved -> {
                    assertThat(saved.isTopicJoiningEnabled()).isFalse();
                    assertThat(saved.getSkillDisplayMode()).isEqualTo(SkillDisplayMode.ALL_ASSOCIATED);
                })
                .verifyComplete();
    }

    private OrganiserSettings settingsOf(
            boolean selfRegistrationEnabled, boolean selfRevocationEnabled, boolean topicApprovalRequired) {
        OrganiserSettings settings = new OrganiserSettings();
        settings.setId(UUID.randomUUID());
        settings.setSingleton(true);
        settings.setSelfRegistrationEnabled(selfRegistrationEnabled);
        settings.setSelfRevocationEnabled(selfRevocationEnabled);
        settings.setTopicApprovalRequired(topicApprovalRequired);
        settings.setParticipantsDirectoryAudience(DirectoryAudience.ORGANISERS_ONLY);
        settings.setMaxGroupMembers(5);
        settings.setTopicJoiningEnabled(true);
        settings.setSkillDisplayMode(SkillDisplayMode.STILL_NEEDED_ONLY);
        settings.setUpdatedAt(Instant.now());
        return settings;
    }
}

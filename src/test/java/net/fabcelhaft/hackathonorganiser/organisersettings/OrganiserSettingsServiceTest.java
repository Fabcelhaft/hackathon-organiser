package net.fabcelhaft.hackathonorganiser.organisersettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

        StepVerifier.create(organiserSettingsService.update(false, null, true))
                .assertNext(saved -> {
                    assertThat(saved.isSelfRegistrationEnabled()).isFalse();
                    assertThat(saved.isSelfRevocationEnabled()).isTrue();
                    assertThat(saved.isTopicApprovalRequired()).isTrue();
                    assertThat(saved.getUpdatedAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void updateWithAllNullsLeavesEveryToggleUnchanged() {
        OrganiserSettings settings = settingsOf(true, false, true);
        when(organiserSettingsRepository.findBySingletonTrue()).thenReturn(Mono.just(settings));
        when(organiserSettingsRepository.save(any(OrganiserSettings.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(organiserSettingsService.update(null, null, null))
                .assertNext(saved -> {
                    assertThat(saved.isSelfRegistrationEnabled()).isTrue();
                    assertThat(saved.isSelfRevocationEnabled()).isFalse();
                    assertThat(saved.isTopicApprovalRequired()).isTrue();
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
        settings.setUpdatedAt(Instant.now());
        return settings;
    }
}

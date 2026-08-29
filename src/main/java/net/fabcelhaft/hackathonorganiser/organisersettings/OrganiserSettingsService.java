package net.fabcelhaft.hackathonorganiser.organisersettings;

import java.time.Instant;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Reads and updates the single {@link OrganiserSettings} row (T007). Every read goes straight
 * through {@link OrganiserSettingsRepository} with no in-memory caching, so a toggle flipped via
 * {@link #update} takes effect on the very next request (FR-023, SC-003) — there is no
 * invalidation logic to get wrong because there is nothing cached to invalidate.
 */
@Service
public class OrganiserSettingsService {

    private final OrganiserSettingsRepository organiserSettingsRepository;

    public OrganiserSettingsService(OrganiserSettingsRepository organiserSettingsRepository) {
        this.organiserSettingsRepository = organiserSettingsRepository;
    }

    /** The current settings row, seeded once at application startup (research.md §4). */
    public Mono<OrganiserSettings> current() {
        return organiserSettingsRepository.findBySingletonTrue();
    }

    /**
     * Updates any combination of the three toggles in one call — a {@code null} argument leaves
     * that toggle unchanged.
     */
    public Mono<OrganiserSettings> update(
            Boolean selfRegistrationEnabled, Boolean selfRevocationEnabled, Boolean topicApprovalRequired) {
        return current().flatMap(settings -> {
            if (selfRegistrationEnabled != null) {
                settings.setSelfRegistrationEnabled(selfRegistrationEnabled);
            }
            if (selfRevocationEnabled != null) {
                settings.setSelfRevocationEnabled(selfRevocationEnabled);
            }
            if (topicApprovalRequired != null) {
                settings.setTopicApprovalRequired(topicApprovalRequired);
            }
            settings.setUpdatedAt(Instant.now());
            return organiserSettingsRepository.save(settings);
        });
    }
}

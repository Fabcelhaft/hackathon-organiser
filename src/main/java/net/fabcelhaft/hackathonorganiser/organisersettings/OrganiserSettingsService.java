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
     * Updates any combination of the seven fields in one call. For the six boolean/enum fields, a
     * {@code null} argument leaves that field unchanged — the existing convention (an entirely
     * absent form field means "don't touch this one"). {@code maxRegistrations} is deliberately
     * different: its own domain range already includes {@code null} as a meaningful value ("no
     * limit", data-model.md), so a submitted blank number field MUST be able to clear a
     * previously-set limit back to unlimited (contracts/organiser-settings.md) — this parameter is
     * therefore always applied, {@code null} included. It is validated ({@code null} or {@code >=
     * 1}) before any field is touched: an invalid value raises {@link
     * OrganiserSettingsConflictException} and applies **no** change at all, to any of the seven
     * fields (FR-007).
     */
    public Mono<OrganiserSettings> update(
            Boolean selfRegistrationEnabled,
            Boolean selfRevocationEnabled,
            Boolean topicApprovalRequired,
            Integer maxRegistrations,
            Boolean selfEditEnabled,
            Boolean skillVisibilityEnabled,
            DirectoryAudience participantsDirectoryAudience) {
        if (maxRegistrations != null && maxRegistrations < 1) {
            return Mono.error(new OrganiserSettingsConflictException(
                    "Maximum registrations must be blank (unlimited) or at least 1"));
        }
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
            settings.setMaxRegistrations(maxRegistrations);
            if (selfEditEnabled != null) {
                settings.setSelfEditEnabled(selfEditEnabled);
            }
            if (skillVisibilityEnabled != null) {
                settings.setSkillVisibilityEnabled(skillVisibilityEnabled);
            }
            if (participantsDirectoryAudience != null) {
                settings.setParticipantsDirectoryAudience(participantsDirectoryAudience);
            }
            settings.setUpdatedAt(Instant.now());
            return organiserSettingsRepository.save(settings);
        });
    }
}

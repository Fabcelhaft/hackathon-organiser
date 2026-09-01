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
     * Updates any combination of the thirteen fields in one call. For most boolean/enum fields, a
     * {@code null} argument leaves that field unchanged — the existing convention (an entirely
     * absent form field means "don't touch this one"). {@code maxRegistrations} and {@code
     * minGroupMembers} are deliberately different: their own domain ranges already include {@code
     * null} as a meaningful value ("no limit"/"unset", data-model.md), so a submitted blank number
     * field MUST be able to clear a previously-set value (contracts/organiser-settings.md,
     * contracts/compliance-settings-and-override.md) — these two parameters are therefore always
     * applied, {@code null} included. {@code maxRegistrations} ({@code null} or {@code >= 1}) and
     * {@code maxGroupMembers} ({@code null} leaves it unchanged, otherwise {@code >= 1}, FR-011b)
     * are each validated before any field is touched; {@code minGroupMembers}, when non-null, is
     * validated against the *effective* Maximum Group Members (the submitted {@code
     * maxGroupMembers} if given, else the current value) so a single call may raise both in the
     * same request (FR-011a). An invalid value raises {@link OrganiserSettingsConflictException}
     * and applies **no** change at all, to any field (FR-007, FR-011a, FR-011b).
     */
    public Mono<OrganiserSettings> update(
            Boolean selfRegistrationEnabled,
            Boolean selfRevocationEnabled,
            Boolean topicApprovalRequired,
            Integer maxRegistrations,
            Boolean selfEditEnabled,
            Boolean skillVisibilityEnabled,
            DirectoryAudience participantsDirectoryAudience,
            Integer maxGroupMembers,
            Integer minGroupMembers,
            Boolean topicJoiningEnabled,
            SkillDisplayMode skillDisplayMode,
            Boolean complianceVisibleToParticipants,
            Boolean teamsLinksEnabled) {
        if (maxRegistrations != null && maxRegistrations < 1) {
            return Mono.error(new OrganiserSettingsConflictException(
                    "Maximum registrations must be blank (unlimited) or at least 1"));
        }
        if (maxGroupMembers != null && maxGroupMembers < 1) {
            return Mono.error(
                    new OrganiserSettingsConflictException("Maximum Group Members must be at least 1"));
        }
        return current().flatMap(settings -> {
            int effectiveMaxGroupMembers =
                    maxGroupMembers != null ? maxGroupMembers : settings.getMaxGroupMembers();
            if (minGroupMembers != null && minGroupMembers > effectiveMaxGroupMembers) {
                return Mono.error(new OrganiserSettingsConflictException(
                        "Minimum Group Members cannot exceed Maximum Group Members"));
            }
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
            if (maxGroupMembers != null) {
                settings.setMaxGroupMembers(maxGroupMembers);
            }
            settings.setMinGroupMembers(minGroupMembers);
            if (topicJoiningEnabled != null) {
                settings.setTopicJoiningEnabled(topicJoiningEnabled);
            }
            if (skillDisplayMode != null) {
                settings.setSkillDisplayMode(skillDisplayMode);
            }
            if (complianceVisibleToParticipants != null) {
                settings.setComplianceVisibleToParticipants(complianceVisibleToParticipants);
            }
            if (teamsLinksEnabled != null) {
                settings.setTeamsLinksEnabled(teamsLinksEnabled);
            }
            settings.setUpdatedAt(Instant.now());
            return organiserSettingsRepository.save(settings);
        });
    }
}

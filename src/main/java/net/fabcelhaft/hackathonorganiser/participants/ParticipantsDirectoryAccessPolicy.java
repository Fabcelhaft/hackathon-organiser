package net.fabcelhaft.hackathonorganiser.participants;

import net.fabcelhaft.hackathonorganiser.organisersettings.DirectoryAudience;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettings;
import org.springframework.stereotype.Component;

/**
 * The single source of truth for the Participants directory's *configurable* audience check
 * (FR-025, FR-026; research.md §6) — reused by both {@link ParticipantsDirectoryController} and
 * {@link net.fabcelhaft.hackathonorganiser.web.CurrentUserModelAdvice}'s nav model advice, so the
 * enforced access and the visible "Participants" menu item can never drift apart.
 *
 * <p>Deliberately not a {@code SecurityConfig} path rule: {@code /organiser/**} → {@code
 * ROLE_ORGANISER} is a *static* role-based gate, while this audience is a *dynamic*,
 * Organiser-configurable setting that can include or exclude Participants specifically — a concept
 * {@code SecurityConfig} has no vocabulary for.
 */
@Component
public class ParticipantsDirectoryAccessPolicy {

    /**
     * Whether a requester is within the configured directory audience: {@code ORGANISERS_ONLY} →
     * organisers only; {@code ORGANISERS_AND_PARTICIPANTS} → organisers or anyone with a
     * Participant record (any status); {@code ALL_AUTHENTICATED} → anyone authenticated (always
     * {@code true} here — the caller is already known to be authenticated by the time this is
     * consulted).
     */
    public boolean isInAudience(OrganiserSettings settings, boolean isOrganiser, boolean hasParticipantRecord) {
        if (isOrganiser) {
            return true;
        }
        DirectoryAudience audience = settings.getParticipantsDirectoryAudience();
        return switch (audience) {
            case ORGANISERS_ONLY -> false;
            case ORGANISERS_AND_PARTICIPANTS -> hasParticipantRecord;
            case ALL_AUTHENTICATED -> true;
        };
    }
}

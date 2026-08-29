package net.fabcelhaft.hackathonorganiser.participant;

/**
 * Thrown when a registration/reactivation attempt would push the {@code ACTIVE} Participant count
 * at or above {@code OrganiserSettings.maxRegistrations} (FR-009, FR-035; research.md §4). Kept
 * distinct from {@link ParticipantConflictException} so callers can render FR-035's specific
 * capacity message rather than a generic validation error.
 */
public class RegistrationCapacityReachedException extends RuntimeException {

    public RegistrationCapacityReachedException(String message) {
        super(message);
    }
}

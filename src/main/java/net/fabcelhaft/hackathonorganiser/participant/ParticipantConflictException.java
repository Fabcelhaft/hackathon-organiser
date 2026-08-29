package net.fabcelhaft.hackathonorganiser.participant;

/**
 * Thrown for a Participant business-invariant violation that should be shown to the Organiser as
 * a friendly, actionable message rather than surfacing a raw database constraint-violation
 * exception: registering a User who already has a Participant record or who doesn't exist
 * (FR-006a, FR-006b), or a Custom Field value submission whose shape doesn't match the field's
 * configured type, or whose selected option doesn't belong to that field (FR-014).
 */
public class ParticipantConflictException extends RuntimeException {

    public ParticipantConflictException(String message) {
        super(message);
    }
}

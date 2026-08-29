package net.fabcelhaft.hackathonorganiser.group;

/**
 * Thrown for a Group business-invariant violation that should be shown to the Organiser as a
 * friendly, actionable message rather than surfacing a raw database constraint-violation
 * exception: creating a Group for a Topic that already has an active one (FR-016a), adding a
 * member that is unknown or already belongs to a different active Group (FR-017), adding a member
 * to a {@code DISBANDED} Group, or disbanding a Group that is already {@code DISBANDED}.
 */
public class GroupConflictException extends RuntimeException {

    public GroupConflictException(String message) {
        super(message);
    }
}

package net.fabcelhaft.hackathonorganiser.customfield;

/**
 * Thrown for a Custom Field business-invariant violation that should be shown to the Organiser as
 * a friendly, actionable message rather than surfacing a raw database constraint-violation
 * exception: a {@code MULTI_SELECT} definition submitted with zero options (FR-012), a duplicate
 * option label within a definition, an attempted {@code field_type} change once a value already
 * exists (FR-012a), or an attempt to delete a definition/option still referenced by a Participant
 * value (FR-023, FR-012b).
 */
public class CustomFieldConflictException extends RuntimeException {

    public CustomFieldConflictException(String message) {
        super(message);
    }
}

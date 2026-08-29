package net.fabcelhaft.hackathonorganiser.organisersettings;

/**
 * Thrown for an Organiser Settings business-invariant violation that should be shown to the
 * Organiser as a friendly, actionable message rather than surfacing a raw database
 * constraint-violation exception: an invalid {@code maxRegistrations} value — {@code null} or
 * {@code >= 1} is required, anything else is rejected with no field changed at all (FR-007).
 */
public class OrganiserSettingsConflictException extends RuntimeException {

    public OrganiserSettingsConflictException(String message) {
        super(message);
    }
}

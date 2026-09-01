package net.fabcelhaft.hackathonorganiser.compliance;

/**
 * Thrown for a Compliance business-invariant violation that should be shown to the Organiser as a
 * friendly, actionable message rather than surfacing a raw database constraint-violation
 * exception: a diversity requirement's minimum below 2 (FR-011d), an unknown or
 * already-configured Custom Field Definition id on add.
 */
public class ComplianceConflictException extends RuntimeException {

    public ComplianceConflictException(String message) {
        super(message);
    }
}

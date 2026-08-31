package net.fabcelhaft.hackathonorganiser.compliance;

/**
 * A Group's automatically-evaluated Compliance outcome (spec.md Key Entities: Compliance Status;
 * data-model.md "Compliance Status" — FR-012, FR-012a, FR-014), produced fresh on every read by
 * {@link ComplianceService#evaluate} — never persisted (research.md §5).
 *
 * <p>"No Group Yet" (FR-014's fourth display state) is deliberately not a value of this enum: a
 * Topic with no active Group has no {@code Group} row to evaluate at all, so callers branch on
 * that case themselves rather than {@link ComplianceService#evaluate} ever returning it.
 */
public enum ComplianceStatus {
    COMPLIANT,
    NOT_COMPLIANT,
    COMPLIANT_OVERRIDE
}

package net.fabcelhaft.hackathonorganiser.group;

/**
 * The closed set of states a Group's {@code status} can be in (spec.md Key Entities: Group;
 * data-model.md "Group" — FR-016, FR-016a, FR-016b). The transition is one-way and terminal:
 * {@code ACTIVE -> DISBANDED} only — there is no "reactivate" operation anywhere in this codebase.
 * A fresh Group row is always created for a Topic that wants to re-form after a disband, rather
 * than any existing row transitioning back to {@code ACTIVE}.
 *
 * <p>Persisted as the {@code groups.status} text column: Spring Data R2DBC's
 * {@code MappingR2dbcConverter} converts a Java enum to/from its {@link Enum#name()} String
 * natively, so no custom converter is registered for this mapping.
 */
public enum GroupStatus {
    ACTIVE,
    DISBANDED
}

package net.fabcelhaft.hackathonorganiser.participant;

/**
 * The closed set of states a Participant's {@code status} can be in (spec.md Key Entities:
 * Participant; data-model.md "Participant" — FR-006b, FR-007). An Organiser may set a Participant
 * to any of these three values at any time; this enum is itself the enforcement of FR-007's
 * "restricted to exactly one of these three at a time" rule — the Java type system rejects any
 * other value before it ever reaches the database.
 *
 * <p>Persisted as the {@code participants.status} text column: Spring Data R2DBC's
 * {@code MappingR2dbcConverter} converts a Java enum to/from its {@link Enum#name()} String
 * natively, so no custom converter is registered for this mapping.
 */
public enum ParticipantStatus {
    ACTIVE,
    NOT_PARTICIPATED,
    REVOKED
}

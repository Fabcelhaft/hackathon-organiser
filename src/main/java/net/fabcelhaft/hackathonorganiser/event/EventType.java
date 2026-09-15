package net.fabcelhaft.hackathonorganiser.event;

/**
 * The fixed, thirteen-entry catalog of domain occurrences the system can publish as an Event
 * (spec.md FR-007, Clarifications session 2026-09-03; data-model.md "Event Type").
 *
 * <p>Persisted as the {@code event_destination_event_types.event_type} text column: Spring Data
 * R2DBC's {@code MappingR2dbcConverter} converts a Java enum to/from its {@link Enum#name()}
 * String natively, so no custom converter is registered for this mapping. There is no backing
 * table for this catalog — it is a fixed Java enum, mirroring {@code AuditEventType}'s existing
 * precedent, not a database-configurable list.
 */
public enum EventType {
    PARTICIPANT_REGISTERED,
    PARTICIPANT_REVOKED,
    PARTICIPANT_NOT_PARTICIPATED,
    USER_CREATED,
    TOPIC_PROPOSED,
    TOPIC_APPROVED,
    PARTICIPANT_JOINED_TOPIC,
    PARTICIPANT_LEFT_TOPIC,
    ORGANISER_ROLE_ADDED,
    ORGANISER_ROLE_REMOVED,
    GROUP_FORMED,
    GROUP_DISBANDED,
    GROUP_COMPLIANCE_CHANGED;

    /** A human-readable label for the Event Type selection checkboxes (organiser/event-destinations/form.html). */
    public String displayName() {
        return switch (this) {
            case PARTICIPANT_REGISTERED -> "Participant registered";
            case PARTICIPANT_REVOKED -> "Participant revoked";
            case PARTICIPANT_NOT_PARTICIPATED -> "Participant not participated";
            case USER_CREATED -> "User created";
            case TOPIC_PROPOSED -> "Topic proposed";
            case TOPIC_APPROVED -> "Topic approved";
            case PARTICIPANT_JOINED_TOPIC -> "Participant joined Topic";
            case PARTICIPANT_LEFT_TOPIC -> "Participant left Topic";
            case ORGANISER_ROLE_ADDED -> "Organiser role added";
            case ORGANISER_ROLE_REMOVED -> "Organiser role removed";
            case GROUP_FORMED -> "Group formed";
            case GROUP_DISBANDED -> "Group disbanded";
            case GROUP_COMPLIANCE_CHANGED -> "Group compliance changed";
        };
    }
}

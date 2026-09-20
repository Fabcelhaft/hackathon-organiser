package net.fabcelhaft.hackathonorganiser.event;

/**
 * The closed set of transports an {@link EventDestination} can use (spec.md Key Entities: Event
 * Destination; data-model.md "Event Destination" — FR-001).
 *
 * <p>Persisted as the {@code event_destinations.type} text column: Spring Data R2DBC's
 * {@code MappingR2dbcConverter} converts a Java enum to/from its {@link Enum#name()} String
 * natively, so no custom converter is registered for this mapping.
 */
public enum EventDestinationType {
    KAFKA,
    HTTP_POST,
    /**
     * Creates a Task inside this application instead of sending the Event anywhere (feature 011
     * spec.md FR-001). A Destination of this type is what the feature-011 spec calls a "Task Rule";
     * it is deliberately not a separate entity, so the existing list, form, unique-name index, and
     * {@link EventDestinationService} serve it unchanged (feature 011 data-model.md "Task Rule").
     */
    TASK
}

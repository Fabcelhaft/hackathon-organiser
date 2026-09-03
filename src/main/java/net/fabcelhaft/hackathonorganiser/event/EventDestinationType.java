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
    HTTP_POST
}

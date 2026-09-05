package net.fabcelhaft.hackathonorganiser.event;

/**
 * Thrown for an Event Destination business-invariant violation that should be shown to the
 * Organiser as a friendly, actionable message rather than surfacing a raw database
 * constraint-violation exception: a missing field required by the chosen {@link
 * EventDestinationType} (FR-002, FR-003, FR-004), a duplicate {@code name} (FR-005), or — the one
 * case with no existing precedent elsewhere in this codebase (research.md/data-model.md "Event
 * Destination" — Concurrency) — a save submitted against a stale {@code updatedAt}, meaning
 * another Organiser edited the same Destination first (FR-018).
 */
public class EventDestinationConflictException extends RuntimeException {

    public EventDestinationConflictException(String message) {
        super(message);
    }
}

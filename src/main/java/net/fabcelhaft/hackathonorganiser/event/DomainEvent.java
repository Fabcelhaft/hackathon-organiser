package net.fabcelhaft.hackathonorganiser.event;

import java.util.Map;

/**
 * An in-memory, never-persisted domain occurrence built by a triggering service (spec.md Key
 * Entities: Event; data-model.md "Domain Event"). {@link EventPublisher#publish(DomainEvent)}
 * turns this into the wire JSON envelope (contracts/event-payloads.md) sent to every currently
 * enabled Destination subscribed to {@code eventType}. Never queried or re-delivered once
 * dispatched (FR-020b) — there is deliberately no repository or table for this type.
 *
 * @param eventType the cataloged {@link EventType} this occurrence matches
 * @param payload one entry per referenced entity (e.g. {@code "topic"}, {@code "participant"}),
 *     keyed exactly as documented per Event Type in data-model.md's "Event Type → payload
 *     composition" table
 */
public record DomainEvent(EventType eventType, Map<String, Object> payload) {}

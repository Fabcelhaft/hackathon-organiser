package net.fabcelhaft.hackathonorganiser.audit;

import java.util.UUID;

/**
 * The acting user and capacity behind one audited action (data-model.md "AuditActor",
 * research.md §2): {@code organiser} reflects the capacity the actor was in for this specific
 * action — which controller/route handled the request — not their general set of privileges. An
 * Organiser-privileged user acting through a self-service route is recorded with
 * {@code organiser = false} for that action.
 */
public record AuditActor(UUID userId, boolean organiser) {}

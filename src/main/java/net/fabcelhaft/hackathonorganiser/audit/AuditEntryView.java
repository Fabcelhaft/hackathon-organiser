package net.fabcelhaft.hackathonorganiser.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * One {@link AuditEntry} resolved for display (data-model.md "AuditEntryView"): {@code
 * actorDisplayName} is joined from {@code users} at read time, the same way {@code
 * GroupService.participantDisplayName} already resolves display names elsewhere. Returned by
 * {@link AuditService#findForTopic}/{@link AuditService#findForParticipant}, most-recent-first,
 * unbounded (no pagination, per the resolved clarification).
 */
public record AuditEntryView(
        Instant occurredAt,
        AuditEventType eventType,
        String actorDisplayName,
        boolean organiser,
        String subjectLabel,
        String oldValue,
        String newValue,
        UUID actionId) {}

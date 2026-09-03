package net.fabcelhaft.hackathonorganiser.audit;

/**
 * The kind of change an {@link AuditEntry} records (data-model.md "Audit Entry"; FR-001, FR-002a,
 * FR-004).
 */
public enum AuditEventType {
    CREATED,
    EDITED,
    STATUS_CHANGED,
    JOINED,
    LEFT,
    DISBANDED,
    DELETED
}

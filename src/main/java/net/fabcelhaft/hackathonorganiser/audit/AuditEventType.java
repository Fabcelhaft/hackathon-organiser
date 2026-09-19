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
    DELETED,
    /**
     * Feature 010 (FR-022): a file was attached to, or removed from, a Topic. Recorded against the
     * Topic itself ({@link AuditSubjectType#TOPIC}) with the file name carried in {@code newValue}
     * (added) or {@code oldValue} (removed) — dedicated types rather than {@code EDITED}, so the
     * trail stays self-describing and the old/new columns keep the meaning feature 006 gave them.
     *
     * <p>{@code audit_entries.event_type} is a free {@code text} column with no CHECK constraint,
     * so these two values need no schema change. They are the first types to set exactly one of
     * old/new, which is why {@code organiser/audit/list.html} renders that pair null-safely.
     */
    ATTACHMENT_ADDED,
    ATTACHMENT_REMOVED
}

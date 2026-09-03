package net.fabcelhaft.hackathonorganiser.audit;

/**
 * What kind of record an {@link AuditEntry}'s {@code subjectId} identifies (data-model.md "Audit
 * Entry", research.md §3). There is deliberately no {@code GROUP} value: every Group-affecting
 * event is recorded against that Group's own Topic instead (research.md §9) — Groups have no audit
 * trail of their own.
 */
public enum AuditSubjectType {
    TOPIC,
    PARTICIPANT
}

package net.fabcelhaft.hackathonorganiser.audit;

import java.time.Instant;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Records and retrieves {@link AuditEntry} rows (T010; data-model.md "AuditService";
 * FR-001-FR-011a). Every mutating service method in this codebase that the spec requires to be
 * audited calls {@link #record} explicitly, chained into its own existing reactive/transactional
 * pipeline (research.md §1) — there is no AOP or event-bus mechanism here.
 */
@Service
public class AuditService {

    private final AuditEntryRepository auditEntryRepository;
    private final UserRepository userRepository;

    public AuditService(AuditEntryRepository auditEntryRepository, UserRepository userRepository) {
        this.auditEntryRepository = auditEntryRepository;
        this.userRepository = userRepository;
    }

    /**
     * Inserts one {@code audit_entries} row. For any Group-affecting event, callers pass {@link
     * AuditSubjectType#TOPIC} and that Group's own {@code topicId} — there is no separate Group
     * subject type (research.md §3, §9). {@code oldValue}/{@code newValue}/{@code actionId} are
     * {@code null} except where the spec calls for them (FR-002a, FR-004a).
     */
    public Mono<AuditEntry> record(
            AuditEventType type,
            AuditActor actor,
            AuditSubjectType subjectType,
            UUID subjectId,
            String subjectLabel,
            String oldValue,
            String newValue,
            UUID actionId) {
        AuditEntry entry = new AuditEntry();
        entry.setEventType(type);
        entry.setActorUserId(actor.userId());
        entry.setOrganiser(actor.organiser());
        entry.setOccurredAt(Instant.now());
        entry.setSubjectType(subjectType);
        entry.setSubjectId(subjectId);
        entry.setSubjectLabel(subjectLabel);
        entry.setOldValue(oldValue);
        entry.setNewValue(newValue);
        entry.setActionId(actionId);
        return auditEntryRepository.save(entry);
    }

    /** A Topic's full history, most-recent-first (FR-011) — also what a Group's "Audit" link resolves to. */
    public Flux<AuditEntryView> findForTopic(UUID topicId) {
        return auditEntryRepository
                .findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(AuditSubjectType.TOPIC, topicId)
                .concatMap(this::toView);
    }

    /** A Participant's full history, most-recent-first (FR-011). */
    public Flux<AuditEntryView> findForParticipant(UUID participantId) {
        return auditEntryRepository
                .findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(AuditSubjectType.PARTICIPANT, participantId)
                .concatMap(this::toView);
    }

    private Mono<AuditEntryView> toView(AuditEntry entry) {
        return userRepository
                .findById(entry.getActorUserId())
                .map(User::getDisplayName)
                .defaultIfEmpty("Unknown user")
                .map(actorDisplayName -> new AuditEntryView(
                        entry.getOccurredAt(),
                        entry.getEventType(),
                        actorDisplayName,
                        entry.isOrganiser(),
                        entry.getSubjectLabel(),
                        entry.getOldValue(),
                        entry.getNewValue(),
                        entry.getActionId()));
    }
}

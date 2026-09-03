package net.fabcelhaft.hackathonorganiser.audit;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

/**
 * Reactive repository for {@link AuditEntry}. Used only for {@code save} (insert, via {@link
 * AuditService#record}) and the {@code findBy...} read methods below — no update/delete method is
 * ever added (FR-010; research.md §6).
 */
public interface AuditEntryRepository extends ReactiveCrudRepository<AuditEntry, UUID> {

    /** Backs {@link AuditService#findForTopic}/{@link AuditService#findForParticipant} (FR-011). */
    Flux<AuditEntry> findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(
            AuditSubjectType subjectType, UUID subjectId);
}

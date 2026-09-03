package net.fabcelhaft.hackathonorganiser.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link AuditService} (T009): {@code record(...)} saves a populated
 * {@link AuditEntry}; {@code findForTopic}/{@code findForParticipant} each query by the matching
 * {@code subject_type} and return entries most-recent-first, with the acting user's display name
 * resolved (research.md §2).
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditEntryRepository auditEntryRepository;

    @Mock
    private UserRepository userRepository;

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditService(auditEntryRepository, userRepository);
    }

    @Test
    void recordSavesAnAuditEntryPopulatedFromItsArguments() {
        AuditActor actor = new AuditActor(UUID.randomUUID(), true);
        UUID subjectId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        when(auditEntryRepository.save(any(AuditEntry.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(auditService.record(
                        AuditEventType.STATUS_CHANGED,
                        actor,
                        AuditSubjectType.PARTICIPANT,
                        subjectId,
                        "Jane Doe",
                        "ACTIVE",
                        "REVOKED",
                        actionId))
                .assertNext(entry -> {
                    assertThat(entry.getEventType()).isEqualTo(AuditEventType.STATUS_CHANGED);
                    assertThat(entry.getActorUserId()).isEqualTo(actor.userId());
                    assertThat(entry.isOrganiser()).isTrue();
                    assertThat(entry.getSubjectType()).isEqualTo(AuditSubjectType.PARTICIPANT);
                    assertThat(entry.getSubjectId()).isEqualTo(subjectId);
                    assertThat(entry.getSubjectLabel()).isEqualTo("Jane Doe");
                    assertThat(entry.getOldValue()).isEqualTo("ACTIVE");
                    assertThat(entry.getNewValue()).isEqualTo("REVOKED");
                    assertThat(entry.getActionId()).isEqualTo(actionId);
                    assertThat(entry.getOccurredAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void findForTopicQueriesByTopicSubjectTypeMostRecentFirst() {
        UUID topicId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        AuditEntry older = entryOf(AuditSubjectType.TOPIC, topicId, actorId, "Older change");
        AuditEntry newer = entryOf(AuditSubjectType.TOPIC, topicId, actorId, "Newer change");
        when(auditEntryRepository.findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                        AuditSubjectType.TOPIC, topicId))
                .thenReturn(Flux.just(newer, older));
        User actor = userOf(actorId, "Jane Doe");
        when(userRepository.findById(actorId)).thenReturn(Mono.just(actor));

        StepVerifier.create(auditService.findForTopic(topicId))
                .assertNext(view -> assertThat(view.subjectLabel()).isEqualTo("Newer change"))
                .assertNext(view -> assertThat(view.subjectLabel()).isEqualTo("Older change"))
                .verifyComplete();
    }

    @Test
    void findForParticipantQueriesByParticipantSubjectType() {
        UUID participantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        AuditEntry entry = entryOf(AuditSubjectType.PARTICIPANT, participantId, actorId, "Jane Doe");
        when(auditEntryRepository.findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                        AuditSubjectType.PARTICIPANT, participantId))
                .thenReturn(Flux.just(entry));
        User actor = userOf(actorId, "Org Anne");
        when(userRepository.findById(actorId)).thenReturn(Mono.just(actor));

        StepVerifier.create(auditService.findForParticipant(participantId))
                .assertNext(view -> {
                    assertThat(view.actorDisplayName()).isEqualTo("Org Anne");
                    assertThat(view.subjectLabel()).isEqualTo("Jane Doe");
                })
                .verifyComplete();
    }

    private AuditEntry entryOf(AuditSubjectType subjectType, UUID subjectId, UUID actorId, String label) {
        AuditEntry entry = new AuditEntry();
        entry.setId(UUID.randomUUID());
        entry.setEventType(AuditEventType.EDITED);
        entry.setActorUserId(actorId);
        entry.setOrganiser(false);
        entry.setOccurredAt(java.time.Instant.now());
        entry.setSubjectType(subjectType);
        entry.setSubjectId(subjectId);
        entry.setSubjectLabel(label);
        return entry;
    }

    private User userOf(UUID id, String displayName) {
        User user = new User();
        user.setId(id);
        user.setDisplayName(displayName);
        return user;
    }
}

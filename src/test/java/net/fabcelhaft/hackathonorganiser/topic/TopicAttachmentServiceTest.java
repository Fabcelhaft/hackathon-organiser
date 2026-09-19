package net.fabcelhaft.hackathonorganiser.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.audit.AuditActor;
import net.fabcelhaft.hackathonorganiser.audit.AuditEntry;
import net.fabcelhaft.hackathonorganiser.audit.AuditEventType;
import net.fabcelhaft.hackathonorganiser.audit.AuditService;
import net.fabcelhaft.hackathonorganiser.audit.AuditSubjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link TopicAttachmentService} (010 T019): the allowlist, size and count limits,
 * empty-file and path-stripping rules (FR-016, FR-016a, FR-017), and the audit entries written on
 * a successful add or remove (FR-022).
 *
 * <p>Every assertion runs through {@link StepVerifier} rather than {@code .block()}, per
 * Constitution Development Workflow #4 — each method under test composes several operators.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TopicAttachmentServiceTest {

    private static final byte[] SOME_BYTES = "hello".getBytes();

    @Mock
    TopicAttachmentRepository attachmentRepository;

    @Mock
    TopicRepository topicRepository;

    @Mock
    AuditService auditService;

    @Mock
    DatabaseClient databaseClient;

    TopicAttachmentService service;

    private UUID topicId;
    private UUID authorId;
    private AuditActor actor;

    @BeforeEach
    void setUp() {
        service = new TopicAttachmentService(attachmentRepository, topicRepository, auditService, databaseClient);
        topicId = UUID.randomUUID();
        authorId = UUID.randomUUID();
        actor = new AuditActor(authorId, false);

        Topic topic = new Topic();
        topic.setId(topicId);
        topic.setName("A Topic");
        topic.setDescription("Description");
        topic.setCreatedByUserId(authorId);
        topic.setApprovalStatus(TopicApprovalStatus.APPROVED);
        topic.setCreatedAt(Instant.now());
        topic.setUpdatedAt(Instant.now());

        when(topicRepository.findById(topicId)).thenReturn(Mono.just(topic));
        when(attachmentRepository.countByTopicId(topicId)).thenReturn(Mono.just(0L));
        when(attachmentRepository.save(any())).thenAnswer(invocation -> {
            TopicAttachment saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(auditService.record(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new AuditEntry()));
    }

    @Test
    void storesAFileWhoseExtensionAndDeclaredTypeAgree() {
        StepVerifier.create(upload("brief.pdf", "application/pdf", SOME_BYTES))
                .assertNext(saved -> {
                    assertThat(saved.getFileName()).isEqualTo("brief.pdf");
                    assertThat(saved.getContentType()).isEqualTo("application/pdf");
                    assertThat(saved.getByteSize()).isEqualTo(SOME_BYTES.length);
                    assertThat(saved.getTopicId()).isEqualTo(topicId);
                    assertThat(saved.getUploadedByUserId()).isEqualTo(authorId);
                })
                .verifyComplete();
    }

    @Test
    void acceptsAContentTypeCarryingParameters() {
        StepVerifier.create(upload("notes.txt", "text/plain; charset=UTF-8", SOME_BYTES))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void rejectsAnAllowedExtensionWhoseDeclaredTypeDoesNotMatchIt() {
        expectRejection(upload("brief.pdf", "image/png", SOME_BYTES), TopicAttachmentType.REJECTION_MESSAGE);
        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void rejectsADisallowedExtensionEvenWhenItsDeclaredTypeIsOnTheAllowlist() {
        expectRejection(upload("payload.exe", "application/pdf", SOME_BYTES), TopicAttachmentType.REJECTION_MESSAGE);
        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void rejectsABlankOrOctetStreamDeclaredType() {
        expectRejection(upload("brief.pdf", null, SOME_BYTES), TopicAttachmentType.REJECTION_MESSAGE);
        expectRejection(
                upload("brief.pdf", "application/octet-stream", SOME_BYTES), TopicAttachmentType.REJECTION_MESSAGE);
        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void rejectsAnEmptyFile() {
        expectRejection(upload("brief.pdf", "application/pdf", new byte[0]), "choose a file");
        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void rejectsAFileOverTenMegabytes() {
        byte[] tooLarge = new byte[10 * 1024 * 1024 + 1];

        expectRejection(upload("big.pdf", "application/pdf", tooLarge), "10 MB or smaller");
        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void acceptsAFileExactlyAtTheTenMegabyteLimit() {
        byte[] exactly = new byte[10 * 1024 * 1024];

        StepVerifier.create(upload("exact.pdf", "application/pdf", exactly))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void rejectsAnEleventhAttachment() {
        when(attachmentRepository.countByTopicId(topicId)).thenReturn(Mono.just(10L));

        expectRejection(upload("eleventh.pdf", "application/pdf", SOME_BYTES), "at most 10 attachments");
        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void stripsPathSegmentsFromTheSubmittedFileName() {
        StepVerifier.create(upload("C:\\Users\\someone\\Desktop\\brief.pdf", "application/pdf", SOME_BYTES))
                .assertNext(saved -> assertThat(saved.getFileName()).isEqualTo("brief.pdf"))
                .verifyComplete();

        StepVerifier.create(upload("/home/someone/notes.txt", "text/plain", SOME_BYTES))
                .assertNext(saved -> assertThat(saved.getFileName()).isEqualTo("notes.txt"))
                .verifyComplete();
    }

    @Test
    void rejectsAFileNameThatIsBlankOnceStripped() {
        expectRejection(upload("   ", "application/pdf", SOME_BYTES), "choose a file");
        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void refusesUploadByAnyoneOtherThanTheAuthorWhenAuthorshipIsRequired() {
        UUID stranger = UUID.randomUUID();

        StepVerifier.create(service.upload(
                        topicId, stranger, true, "brief.pdf", "application/pdf", SOME_BYTES, new AuditActor(
                                stranger, false)))
                .expectErrorMatches(error -> error instanceof TopicAttachmentConflictException)
                .verify();
        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void recordsAnAttachmentAddedAuditEntryNamingTheFile() {
        StepVerifier.create(upload("brief.pdf", "application/pdf", SOME_BYTES))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<String> newValue = ArgumentCaptor.forClass(String.class);
        verify(auditService)
                .record(
                        eq(AuditEventType.ATTACHMENT_ADDED),
                        eq(actor),
                        eq(AuditSubjectType.TOPIC),
                        eq(topicId),
                        eq("A Topic"),
                        eq(null),
                        newValue.capture(),
                        eq(null));
        assertThat(newValue.getValue()).isEqualTo("brief.pdf");
    }

    @Test
    void recordsAnAttachmentRemovedAuditEntryNamingTheFile() {
        UUID attachmentId = UUID.randomUUID();
        TopicAttachment existing = new TopicAttachment();
        existing.setId(attachmentId);
        existing.setTopicId(topicId);
        existing.setFileName("obsolete.pdf");
        when(attachmentRepository.findByIdAndTopicId(attachmentId, topicId)).thenReturn(Mono.just(existing));
        when(attachmentRepository.deleteById(attachmentId)).thenReturn(Mono.empty());

        StepVerifier.create(service.remove(topicId, attachmentId, authorId, true, actor))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<String> oldValue = ArgumentCaptor.forClass(String.class);
        verify(auditService)
                .record(
                        eq(AuditEventType.ATTACHMENT_REMOVED),
                        eq(actor),
                        eq(AuditSubjectType.TOPIC),
                        eq(topicId),
                        eq("A Topic"),
                        oldValue.capture(),
                        eq(null),
                        eq(null));
        assertThat(oldValue.getValue()).isEqualTo("obsolete.pdf");
    }

    @Test
    void removingAnAttachmentThatBelongsToAnotherTopicCompletesEmpty() {
        UUID attachmentId = UUID.randomUUID();
        when(attachmentRepository.findByIdAndTopicId(attachmentId, topicId)).thenReturn(Mono.empty());

        StepVerifier.create(service.remove(topicId, attachmentId, authorId, true, actor))
                .verifyComplete();
        verify(attachmentRepository, never()).deleteById(any(UUID.class));
    }

    private Mono<TopicAttachment> upload(String fileName, String contentType, byte[] bytes) {
        return service.upload(topicId, authorId, true, fileName, contentType, bytes, actor);
    }

    private void expectRejection(Mono<TopicAttachment> attempt, String messageFragment) {
        StepVerifier.create(attempt)
                .expectErrorMatches(error -> error instanceof TopicAttachmentConflictException
                        && error.getMessage().contains(messageFragment))
                .verify();
    }
}

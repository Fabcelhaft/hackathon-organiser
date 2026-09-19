package net.fabcelhaft.hackathonorganiser.topic;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.audit.AuditActor;
import net.fabcelhaft.hackathonorganiser.audit.AuditEventType;
import net.fabcelhaft.hackathonorganiser.audit.AuditService;
import net.fabcelhaft.hackathonorganiser.audit.AuditSubjectType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Add, list, remove and serve {@link TopicAttachment} files (010 T030; FR-012-FR-022).
 *
 * <p>Every validation rule runs before any write, so a rejected upload is never partially stored —
 * the same discipline {@code ContentImageService.upload} already follows.
 *
 * <p><b>Audit (FR-022):</b> a successful add records {@link AuditEventType#ATTACHMENT_ADDED} and a
 * successful removal {@link AuditEventType#ATTACHMENT_REMOVED}, both against the owning Topic
 * (there is no attachment subject type, matching how feature 006 records Group events against
 * their Topic) with the file name carried in the one relevant old/new column.
 *
 * <p><b>Authorization</b> is enforced primarily at the controllers, which already own the
 * 404-vs-403 split for Topic edit routes. The {@code requireAuthor} flag here is a defensive
 * second check for the participant-facing routes; organiser routes pass {@code false}, since an
 * Organiser may manage any Topic's attachments (FR-013).
 */
@Service
public class TopicAttachmentService {

    /** FR-016: fixed, not configurable per instance (spec Clarifications). */
    static final int MAX_BYTES = 10 * 1024 * 1024;

    /** FR-016: fixed, not configurable per instance (spec Clarifications). */
    static final long MAX_PER_TOPIC = 10;

    private static final String CHOOSE_A_FILE = "Please choose a file to upload";

    private final TopicAttachmentRepository attachmentRepository;
    private final TopicRepository topicRepository;
    private final AuditService auditService;
    private final DatabaseClient databaseClient;

    public TopicAttachmentService(
            TopicAttachmentRepository attachmentRepository,
            TopicRepository topicRepository,
            AuditService auditService,
            DatabaseClient databaseClient) {
        this.attachmentRepository = attachmentRepository;
        this.topicRepository = topicRepository;
        this.auditService = auditService;
        this.databaseClient = databaseClient;
    }

    /**
     * A Topic's attachments, oldest first (FR-018), as metadata only — the {@code data} column is
     * deliberately never selected here, so listing ten 10 MB attachments costs one small query
     * rather than 100 MB of buffers. {@code id} is included in the ordering so the sequence stays
     * stable when two rows share a {@code created_at}.
     */
    public Flux<TopicAttachmentSummary> listFor(UUID topicId) {
        return databaseClient
                .sql(
                        """
                        SELECT id, topic_id, file_name, content_type, byte_size, created_at
                        FROM topic_attachments
                        WHERE topic_id = :tid
                        ORDER BY created_at, id
                        """)
                .bind("tid", topicId)
                .map(row -> new TopicAttachmentSummary(
                        row.get("id", UUID.class),
                        row.get("topic_id", UUID.class),
                        row.get("file_name", String.class),
                        row.get("content_type", String.class),
                        row.get("byte_size", Integer.class),
                        row.get("created_at", Instant.class)))
                .all();
    }

    /**
     * Stores one file against a Topic, rejecting with a {@link TopicAttachmentConflictException}
     * whose message names the limit or lists the accepted types. Completes empty when no Topic
     * exists with the given id, so the caller can answer 404.
     */
    public Mono<TopicAttachment> upload(
            UUID topicId,
            UUID requesterUserId,
            boolean requireAuthor,
            String fileName,
            String contentType,
            byte[] data,
            AuditActor actor) {
        String cleanName = stripPathSegments(fileName);
        if (cleanName.isBlank()) {
            return Mono.error(new TopicAttachmentConflictException(CHOOSE_A_FILE));
        }
        if (data == null || data.length == 0) {
            return Mono.error(new TopicAttachmentConflictException(CHOOSE_A_FILE));
        }
        if (data.length > MAX_BYTES) {
            return Mono.error(new TopicAttachmentConflictException("Attachments must be 10 MB or smaller"));
        }
        if (!TopicAttachmentType.isAllowed(cleanName, contentType)) {
            return Mono.error(new TopicAttachmentConflictException(TopicAttachmentType.REJECTION_MESSAGE));
        }
        return topicRepository.findById(topicId).flatMap(topic -> {
            if (requireAuthor && !topic.getCreatedByUserId().equals(requesterUserId)) {
                return Mono.error(
                        new TopicAttachmentConflictException("You may only change your own Topic's attachments"));
            }
            return attachmentRepository.countByTopicId(topicId).flatMap(count -> {
                if (count >= MAX_PER_TOPIC) {
                    return Mono.error(new TopicAttachmentConflictException(
                            "A Topic can have at most " + MAX_PER_TOPIC + " attachments"));
                }
                TopicAttachment attachment = new TopicAttachment();
                attachment.setTopicId(topicId);
                attachment.setFileName(cleanName);
                attachment.setContentType(TopicAttachmentType.normalizeContentType(contentType));
                attachment.setByteSize(data.length);
                attachment.setData(data);
                attachment.setUploadedByUserId(requesterUserId);
                attachment.setCreatedAt(Instant.now());
                return attachmentRepository
                        .save(attachment)
                        .flatMap(saved -> record(
                                        AuditEventType.ATTACHMENT_ADDED, actor, topic, null, saved.getFileName())
                                .thenReturn(saved));
            });
        });
    }

    /**
     * Removes one attachment from a Topic. Completes empty when the Topic is unknown, or the
     * attachment does not exist under <em>this</em> Topic, so the caller can answer 404 without
     * distinguishing the two.
     */
    public Mono<TopicAttachment> remove(
            UUID topicId, UUID attachmentId, UUID requesterUserId, boolean requireAuthor, AuditActor actor) {
        return topicRepository.findById(topicId).flatMap(topic -> {
            if (requireAuthor && !topic.getCreatedByUserId().equals(requesterUserId)) {
                return Mono.error(
                        new TopicAttachmentConflictException("You may only change your own Topic's attachments"));
            }
            return attachmentRepository
                    .findByIdAndTopicId(attachmentId, topicId)
                    .flatMap(attachment -> attachmentRepository
                            .deleteById(attachmentId)
                            .then(record(
                                    AuditEventType.ATTACHMENT_REMOVED,
                                    actor,
                                    topic,
                                    attachment.getFileName(),
                                    null))
                            .thenReturn(attachment));
        });
    }

    /**
     * The full row, bytes included, for the download route — matched on the {@code (id, topicId)}
     * pair so one Topic's URL can never serve another Topic's attachment (FR-020).
     */
    public Mono<TopicAttachment> findForDownload(UUID topicId, UUID attachmentId) {
        return attachmentRepository.findByIdAndTopicId(attachmentId, topicId);
    }

    private Mono<Void> record(
            AuditEventType type, AuditActor actor, Topic topic, String oldValue, String newValue) {
        return auditService
                .record(
                        type,
                        actor,
                        AuditSubjectType.TOPIC,
                        topic.getId(),
                        topic.getName(),
                        oldValue,
                        newValue,
                        null)
                .then();
    }

    /**
     * The last segment of a submitted file name. Browsers normally send a bare name, but some send
     * a full path (notably older Windows browsers), and a name containing {@code ../} must never
     * reach a header or a screen intact.
     */
    private static String stripPathSegments(String fileName) {
        if (fileName == null) {
            return "";
        }
        String trimmed = fileName.trim();
        int lastSlash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return (lastSlash < 0 ? trimmed : trimmed.substring(lastSlash + 1)).trim();
    }

    /**
     * A Topic Attachment without its bytes — what every listing renders (010 data-model.md).
     */
    public record TopicAttachmentSummary(
            UUID id, UUID topicId, String fileName, String contentType, int byteSize, Instant createdAt) {

        /**
         * The size as a person reads it, for the attachments tables. Kept on the read model rather
         * than in a Thymeleaf-accessible bean so the formatting travels with the data it describes
         * and both detail views and both edit screens render it identically.
         */
        public String humanSize() {
            if (byteSize < 1024) {
                return byteSize + " B";
            }
            if (byteSize < 1024 * 1024) {
                return String.format(Locale.ROOT, "%.1f KB", byteSize / 1024.0);
            }
            return String.format(Locale.ROOT, "%.1f MB", byteSize / (1024.0 * 1024.0));
        }
    }
}

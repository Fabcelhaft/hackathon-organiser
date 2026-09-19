package net.fabcelhaft.hackathonorganiser.topic;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A file attached to a {@link Topic} by its author or an Organiser (010 spec.md Key Entities:
 * Topic Attachment; 010 data-model.md — FR-012-FR-021).
 *
 * <p>{@code id} is left {@code null} on construction: PostgreSQL assigns it via the
 * {@code topic_attachments.id} column's {@code DEFAULT uuidv7()} — the same convention every other
 * entity here follows, and the value that appears in the download URL
 * {@code /topics/{topicId}/attachments/{id}}.
 *
 * <p>Every field is immutable after upload — an attachment is only ever added or removed, never
 * edited in place, so there is no {@code updatedAt}. The owning Topic's row carries
 * {@code ON DELETE CASCADE}, which is what makes FR-021 hold whenever a Topic delete route is
 * added (none exists today).
 *
 * <p>{@code data} holds the bytes as a {@code bytea}, mirroring
 * {@link net.fabcelhaft.hackathonorganiser.content.ContentImage}. Listings deliberately never load
 * it — see {@link TopicAttachmentService#listFor} — so a detail page with ten 10 MB attachments
 * still costs one small metadata query.
 */
@Table("topic_attachments")
public class TopicAttachment {

    @Id
    private UUID id;

    private UUID topicId;

    private String fileName;

    private String contentType;

    private int byteSize;

    private byte[] data;

    private UUID uploadedByUserId;

    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTopicId() {
        return topicId;
    }

    public void setTopicId(UUID topicId) {
        this.topicId = topicId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public int getByteSize() {
        return byteSize;
    }

    public void setByteSize(int byteSize) {
        this.byteSize = byteSize;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public UUID getUploadedByUserId() {
        return uploadedByUserId;
    }

    public void setUploadedByUserId(UUID uploadedByUserId) {
        this.uploadedByUserId = uploadedByUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

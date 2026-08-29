package net.fabcelhaft.hackathonorganiser.content;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * An Organiser-uploaded image stored as a database {@code bytea} (spec.md Key Entities: Content
 * Image; data-model.md "Content Image" — FR-024–FR-029, research.md §2).
 *
 * <p>{@code id} is left {@code null} on construction: PostgreSQL assigns it via the
 * {@code content_images.id} column's {@code DEFAULT uuidv7()} (research.md §1) — also the value
 * embedded in the stable {@code /content-images/{id}} reference an Organiser copies into markdown
 * (FR-025).
 *
 * <p>{@code data}/{@code contentType}/{@code byteSize} are immutable after upload; only
 * {@code altText} is ever editable in place (FR-025b).
 */
@Table("content_images")
public class ContentImage {

    @Id
    private UUID id;

    private String altText;

    private String contentType;

    private int byteSize;

    private byte[] data;

    private Instant createdAt;

    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getAltText() {
        return altText;
    }

    public void setAltText(String altText) {
        this.altText = altText;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

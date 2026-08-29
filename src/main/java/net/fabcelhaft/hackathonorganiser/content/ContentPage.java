package net.fabcelhaft.hackathonorganiser.content;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * An Organiser-authored markdown page (spec.md Key Entities: Content Page; data-model.md
 * "Content Page" — FR-018–FR-020a). At most one Content Page may have {@code isHomepage = true}
 * at a time — enforced by the partial unique index {@code content_pages_is_homepage_key}
 * (schema.sql, FR-019).
 *
 * <p>{@code id} is left {@code null} on construction: PostgreSQL assigns it via the
 * {@code content_pages.id} column's {@code DEFAULT uuidv7()} (research.md §1) — no
 * application-side ID generation exists anywhere in this codebase.
 *
 * <p>{@code bodyMarkdown} is the raw Organiser-authored source; sanitized HTML is derived at
 * render time by {@link MarkdownRenderer} and never stored (research.md §1).
 */
@Table("content_pages")
public class ContentPage {

    @Id
    private UUID id;

    private String title;

    private String bodyMarkdown;

    private int sortIndex;

    private boolean isHomepage;

    private Instant createdAt;

    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBodyMarkdown() {
        return bodyMarkdown;
    }

    public void setBodyMarkdown(String bodyMarkdown) {
        this.bodyMarkdown = bodyMarkdown;
    }

    public int getSortIndex() {
        return sortIndex;
    }

    public void setSortIndex(int sortIndex) {
        this.sortIndex = sortIndex;
    }

    public boolean isHomepage() {
        return isHomepage;
    }

    public void setHomepage(boolean homepage) {
        isHomepage = homepage;
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

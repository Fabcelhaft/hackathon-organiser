package net.fabcelhaft.hackathonorganiser.content;

/**
 * Thrown for a Content Image business-invariant violation that should be shown to the Organiser as
 * a friendly, actionable message rather than surfacing a raw error: a non-PNG/JPEG/GIF/WebP or
 * over-5MB upload (FR-029), a blank {@code alt_text} (FR-025a), or a delete attempt while the
 * image is still referenced by one or more Content Pages (FR-028) — naming those pages by title.
 */
public class ContentImageConflictException extends RuntimeException {

    public ContentImageConflictException(String message) {
        super(message);
    }
}

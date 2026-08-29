package net.fabcelhaft.hackathonorganiser.content;

/**
 * Thrown for a Content Page business-invariant violation that should be shown to the Organiser as
 * a friendly, actionable message rather than surfacing a raw database constraint-violation
 * exception: a missing {@code title}/{@code body_markdown} (FR-037).
 */
public class ContentPageConflictException extends RuntimeException {

    public ContentPageConflictException(String message) {
        super(message);
    }
}

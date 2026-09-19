package net.fabcelhaft.hackathonorganiser.topic;

/**
 * Thrown for a Topic Attachment business-invariant violation that should reach the author as a
 * friendly, actionable message on the re-rendered edit form rather than as a raw error: a missing
 * or empty file (FR-017), a file over 10 MB or an eleventh attachment (FR-016), or a file whose
 * type is not on the allowlist (FR-016a).
 *
 * <p>Mirrors {@link net.fabcelhaft.hackathonorganiser.content.ContentImageConflictException}'s role
 * for the Content Image library, and is handled the same way: caught in the controller with
 * {@code onErrorResume} and turned into an {@code error} model attribute.
 */
public class TopicAttachmentConflictException extends RuntimeException {

    public TopicAttachmentConflictException(String message) {
        super(message);
    }
}

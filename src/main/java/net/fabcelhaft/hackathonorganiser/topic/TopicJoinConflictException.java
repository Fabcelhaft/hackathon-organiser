package net.fabcelhaft.hackathonorganiser.topic;

/**
 * Thrown for a self-service Join eligibility violation that should be shown to the Participant as
 * a friendly, actionable message rather than surfacing a raw error: Topic joining currently
 * disabled instance-wide (FR-020b), the requester has no Participant record or a non-{@code
 * ACTIVE} status (FR-007b), or the Group-level rejection reasons {@link
 * net.fabcelhaft.hackathonorganiser.group.GroupConflictException} already covers, translated
 * through this same exception type so the controller has one thing to catch (FR-026).
 */
public class TopicJoinConflictException extends RuntimeException {

    public TopicJoinConflictException(String message) {
        super(message);
    }
}

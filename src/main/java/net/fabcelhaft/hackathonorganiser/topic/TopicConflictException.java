package net.fabcelhaft.hackathonorganiser.topic;

/**
 * Thrown for a Topic business-invariant violation that should be shown to the Organiser as a
 * friendly, actionable message rather than surfacing a raw database constraint-violation
 * exception: a missing {@code name}/{@code description}/{@code created_by_user_id} or an unknown
 * {@code created_by_user_id} on create (FR-015), or a {@code skill_ids[]} selection containing an
 * unknown Skill.
 */
public class TopicConflictException extends RuntimeException {

    public TopicConflictException(String message) {
        super(message);
    }
}

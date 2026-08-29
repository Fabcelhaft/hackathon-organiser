package net.fabcelhaft.hackathonorganiser.skill;

/**
 * Thrown for a Skill business-invariant violation that should be shown to the Organiser as a
 * friendly, actionable message rather than surfacing a raw database constraint-violation
 * exception: a case-insensitive duplicate name on create/rename (FR-008a), or an attempt to
 * delete a Skill still referenced by a Participant or Topic (FR-023).
 */
public class SkillConflictException extends RuntimeException {

    public SkillConflictException(String message) {
        super(message);
    }
}

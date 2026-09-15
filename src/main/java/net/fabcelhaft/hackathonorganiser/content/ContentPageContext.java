package net.fabcelhaft.hackathonorganiser.content;

/**
 * The context a {@link ContentPage} is designated for (Feature 008, data-model.md
 * "ContentPageContext"; FR-012, FR-013). Persisted as the {@code content_pages.context} text column
 * via {@code Enum#name()} — the same native Spring Data R2DBC round-trip {@code ParticipantStatus}
 * and {@code TopicApprovalStatus} already rely on (research.md §1).
 *
 * <p>{@link #NONE} is the explicit "ordinary Info page" value, not a null: exactly the pages holding
 * it populate the Info menu (FR-014). Every other value is held by at most one page at a time —
 * enforced by {@code ContentPageService}'s swap logic plus the partial unique index
 * {@code content_pages_context_key} (schema.sql).
 */
public enum ContentPageContext {
    NONE("None"),
    HOMEPAGE("Homepage"),
    TOPIC_CREATION("Topic creation"),
    USER_REGISTRATION("User registration");

    private final String displayName;

    ContentPageContext(String displayName) {
        this.displayName = displayName;
    }

    /** The human-readable label used by the organiser form, overview and delete confirmation. */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Lenient parse for the organiser form's closed single-select (data-model.md "Validation rules"):
     * a blank or unrecognised value falls back to {@link #NONE} rather than erroring, since the
     * browser itself constrains the control to the four known options.
     */
    public static ContentPageContext fromFormValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            return NONE;
        }
    }
}

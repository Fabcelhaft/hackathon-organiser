package net.fabcelhaft.hackathonorganiser.topic;

/**
 * The closed set of states a Topic's {@code approval_status} can be in (data-model.md "Topic" —
 * FR-013, FR-014, FR-016). Set once at creation by {@link TopicService#propose} from the current
 * {@code OrganiserSettings.topicApprovalRequired} value; changed afterward only by the explicit
 * Organiser approve action ({@link TopicService#approve}) — never bulk-updated when the setting
 * itself later changes (FR-016 is not retroactive).
 *
 * <p>Persisted as the {@code topics.approval_status} text column: Spring Data R2DBC's
 * {@code MappingR2dbcConverter} converts a Java enum to/from its {@link Enum#name()} String
 * natively, so no custom converter is registered for this mapping.
 */
public enum TopicApprovalStatus {
    PENDING,
    APPROVED
}

package net.fabcelhaft.hackathonorganiser.organisersettings;

/**
 * Controls whether the Home Page's and Topic Overview's Skills columns show only a Topic's
 * still-uncovered needed Skills or every Skill it needs regardless of coverage (spec.md Key
 * Entities: Organiser Settings; data-model.md "Skill Display Mode" — FR-017, FR-018). Resolved at
 * read time by {@code TopicDiscoveryService} against a Topic's current Group's active members'
 * own Skills (research.md §7).
 *
 * <p>Persisted as the {@code organiser_settings.skill_display_mode} text column: Spring Data
 * R2DBC's {@code MappingR2dbcConverter} converts a Java enum to/from its {@link Enum#name()}
 * String natively, so no custom converter is registered for this mapping.
 */
public enum SkillDisplayMode {
    STILL_NEEDED_ONLY,
    ALL_ASSOCIATED
}

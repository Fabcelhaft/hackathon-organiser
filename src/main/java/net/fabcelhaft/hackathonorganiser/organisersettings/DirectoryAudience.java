package net.fabcelhaft.hackathonorganiser.organisersettings;

/**
 * The configurable audience allowed to view the Participants directory (spec.md Key Entities:
 * Organiser Settings; data-model.md "Organiser Settings" — FR-025). Defaults to the most
 * restrictive tier, {@link #ORGANISERS_ONLY}, consistent with the private-by-default posture the
 * spec already applies to Skill visibility and to Overview-without-Public fields (research.md §5).
 *
 * <p>Persisted as the {@code organiser_settings.participants_directory_audience} text column:
 * Spring Data R2DBC's {@code MappingR2dbcConverter} converts a Java enum to/from its
 * {@link Enum#name()} String natively, so no custom converter is registered for this mapping.
 */
public enum DirectoryAudience {
    ORGANISERS_ONLY,
    ORGANISERS_AND_PARTICIPANTS,
    ALL_AUTHENTICATED
}

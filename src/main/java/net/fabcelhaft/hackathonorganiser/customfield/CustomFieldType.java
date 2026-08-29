package net.fabcelhaft.hackathonorganiser.customfield;

/**
 * The shapes a Custom Field Definition's value can take (spec.md Key Entities: Custom Field;
 * data-model.md "Custom Field Definition" — FR-011, FR-012, FR-013). {@code SINGLE_SELECT} behaves
 * like {@code MULTI_SELECT} for options/locking but caps Participant selections to one
 * (research.md §2). {@code COUNTRY} is fixed — at most one row of this type ever exists, never
 * created/deleted by an Organiser, its options computed at read time from
 * {@link net.fabcelhaft.hackathonorganiser.customfield.IsoCountryCatalog} rather than stored
 * (research.md §1).
 *
 * <p>Persisted as the {@code custom_field_definitions.field_type} text column: Spring Data R2DBC's
 * {@code MappingR2dbcConverter} converts a Java enum to/from its {@link Enum#name()} String
 * natively, so no custom converter is registered for this mapping.
 */
public enum CustomFieldType {
    FREE_TEXT,
    MULTI_SELECT,
    SINGLE_SELECT,
    COUNTRY
}

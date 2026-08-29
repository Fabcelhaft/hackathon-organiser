package net.fabcelhaft.hackathonorganiser.customfield;

/**
 * The two shapes a Custom Field Definition's value can take (spec.md Key Entities: Custom Field;
 * data-model.md "Custom Field Definition" — FR-011, FR-012).
 *
 * <p>Persisted as the {@code custom_field_definitions.field_type} text column: Spring Data R2DBC's
 * {@code MappingR2dbcConverter} converts a Java enum to/from its {@link Enum#name()} String
 * natively, so no custom converter is registered for this mapping.
 */
public enum CustomFieldType {
    FREE_TEXT,
    MULTI_SELECT
}

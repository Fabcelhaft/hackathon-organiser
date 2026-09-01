package net.fabcelhaft.hackathonorganiser.customfield;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The static, system-maintained ISO 3166-1 alpha-2 country list backing the built-in {@code
 * COUNTRY} Custom Field (FR-013; research.md §1) — sourced entirely from the JDK's own {@link
 * Locale} API, no new dependency and no database table. {@code Locale.of(...)} is used rather than
 * the two-arg {@code new Locale(String, String)} constructor, which has been deprecated since Java
 * 19 in favor of the {@code of(...)} factory methods.
 */
public final class IsoCountryCatalog {

    private IsoCountryCatalog() {}

    /** Display names that override the JDK's own, e.g. because it lags common usage. */
    private static final Map<String, String> DISPLAY_NAME_OVERRIDES = Map.of("PS", "Palestine");

    /** Every ISO 3166-1 alpha-2 country, sorted by English display name. */
    public static List<Country> all() {
        return Arrays.stream(Locale.getISOCountries())
                .map(code -> new Country(code, displayName(code)))
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();
    }

    private static String displayName(String code) {
        return DISPLAY_NAME_OVERRIDES.getOrDefault(
                code, Locale.of("", code).getDisplayCountry(Locale.ENGLISH));
    }

    /** One ISO 3166-1 alpha-2 country: its code (e.g. {@code "DE"}) and English display name. */
    public record Country(String code, String name) {}
}

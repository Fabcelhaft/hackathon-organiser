package net.fabcelhaft.hackathonorganiser.customfield;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.List;
import net.fabcelhaft.hackathonorganiser.customfield.IsoCountryCatalog.Country;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IsoCountryCatalog#all()} (T001; research.md §1) — a plain synchronous
 * method with no reactive chain, so ordinary JUnit assertions are used rather than
 * {@link reactor.test.StepVerifier}.
 */
class IsoCountryCatalogTest {

    @Test
    void returnsOneEntryPerIsoCountryIncludingTheseThreeCodes() {
        List<Country> all = IsoCountryCatalog.all();

        assertThat(all).extracting(Country::code).contains("PS", "DE", "US");
        assertThat(all).extracting(Country::code).doesNotHaveDuplicates();
    }

    @Test
    void everyCodeHasANonBlankDisplayName() {
        assertThat(IsoCountryCatalog.all()).allSatisfy(country -> assertThat(country.name())
                .isNotBlank());
    }

    @Test
    void isSortedByDisplayName() {
        List<Country> all = IsoCountryCatalog.all();

        assertThat(all).isSortedAccordingTo(Comparator.comparing(Country::name));
    }
}

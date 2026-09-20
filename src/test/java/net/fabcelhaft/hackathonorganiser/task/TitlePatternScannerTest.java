package net.fabcelhaft.hackathonorganiser.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link TitlePatternScanner} (T009) — every rejection row of
 * contracts/title-pattern.md "Validation, at save time" (FR-007), plus the literal-brace rule
 * (FR-009).
 */
class TitlePatternScannerTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Review {{topic.name", // unclosed wildcard
                "Review {{}}", // empty path
                "Review {{.name}}", // leading empty segment
                "Review {{topic.}}", // trailing empty segment
                "Review {{topic..name}}", // doubled dot
            })
    void rejectsMalformedWildcards(String pattern) {
        assertThat(TitlePatternScanner.validationError(pattern)).isNotNull();
    }

    @Test
    void rejectsAnEmptyPattern() {
        assertThat(TitlePatternScanner.validationError("")).isNotNull();
        assertThat(TitlePatternScanner.validationError("   ")).isNotNull();
        assertThat(TitlePatternScanner.validationError(null)).isNotNull();
    }

    /** FR-007: the rejection must name the offending wildcard, not just say "invalid". */
    @Test
    void theRejectionMessageNamesTheOffendingWildcard() {
        assertThat(TitlePatternScanner.validationError("Review {{topic..name}}")).contains("topic..name");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Review new topic: {{topic.name}}",
                "{{eventType}} — {{topic.name}}",
                "{{customFields.0.definition.label}}",
                "No wildcards at all",
            })
    void acceptsWellFormedPatterns(String pattern) {
        assertThat(TitlePatternScanner.validationError(pattern)).isNull();
    }

    /** FR-009: a single brace is literal text and is never rejected. */
    @Test
    void acceptsSingleBracesAsLiteralText() {
        assertThat(TitlePatternScanner.validationError("Set {timeout} for {{topic.name}}"))
                .isNull();
    }

    /**
     * FR-012: paths are never checked against the Event catalog — that is what lets one Rule serve
     * several Event Types whose data differs.
     */
    @Test
    void acceptsAPathThatMatchesNothing() {
        assertThat(TitlePatternScanner.validationError("{{nothing.like.this.exists}}"))
                .isNull();
    }

    @Test
    void splitsAPatternIntoLiteralsAndWildcards() {
        var segments = TitlePatternScanner.scan("Review {{topic.name}} now");

        assertThat(segments).hasSize(3);
        assertThat(segments.get(0).wildcard()).isFalse();
        assertThat(segments.get(0).text()).isEqualTo("Review ");
        assertThat(segments.get(1).wildcard()).isTrue();
        assertThat(segments.get(1).text()).isEqualTo("topic.name");
        assertThat(segments.get(2).wildcard()).isFalse();
        assertThat(segments.get(2).text()).isEqualTo(" now");
    }
}

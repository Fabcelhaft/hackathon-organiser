package net.fabcelhaft.hackathonorganiser.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.fabcelhaft.hackathonorganiser.event.EventType;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TitlePatternResolver} — both tables of contracts/title-pattern.md (T010)
 * and the assembly order of research.md §5 (T011).
 */
class TitlePatternResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String resolve(String pattern, String rawJson) {
        return TitlePatternResolver.resolveTitle(pattern, json(rawJson), "Some Rule", EventType.TOPIC_PROPOSED);
    }

    // ---------------------------------------------------------------- T010 (a) "Walking a path"

    @Test
    void aDigitSegmentIndexesAnArray() {
        assertThat(resolve("{{items.1}}", "{\"items\":[\"first\",\"second\"]}")).isEqualTo("second");
    }

    @Test
    void aNestedDigitSegmentReachesInsideAListEntry() {
        String payload = "{\"customFields\":[{\"definition\":{\"label\":\"Dietary requirements\"}}]}";
        assertThat(resolve("{{customFields.0.definition.label}}", payload)).isEqualTo("Dietary requirements");
    }

    @Test
    void aNonDigitSegmentOnAnArrayIsMissing() {
        assertThat(resolve("x{{items.name}}y", "{\"items\":[\"first\"]}")).isEqualTo("xy");
    }

    @Test
    void anOutOfRangeIndexIsMissing() {
        assertThat(resolve("x{{items.9}}y", "{\"items\":[\"first\"]}")).isEqualTo("xy");
    }

    /** An object with a numeric-looking key resolves as a field, never as a position. */
    @Test
    void aDigitSegmentOnAnObjectResolvesAsAFieldName() {
        assertThat(resolve("{{counts.0}}", "{\"counts\":{\"0\":\"zero\"}}")).isEqualTo("zero");
    }

    @Test
    void aPathThroughAScalarIsMissing() {
        assertThat(resolve("x{{topic.name.deeper}}y", "{\"topic\":{\"name\":\"Robot Arm\"}}"))
                .isEqualTo("xy");
    }

    // -------------------------------------------------------------- T010 (b) "What gets written"

    @Test
    void aStringIsWrittenUnchanged() {
        assertThat(resolve("Review: {{topic.name}}", "{\"topic\":{\"name\":\"Robot Arm\"}}"))
                .isEqualTo("Review: Robot Arm");
    }

    @Test
    void aNumberIsWrittenAsItAppears() {
        assertThat(resolve("{{n}}", "{\"n\":42}")).isEqualTo("42");
    }

    @Test
    void aBooleanIsWrittenAsTrueOrFalse() {
        assertThat(resolve("{{a}}/{{b}}", "{\"a\":true,\"b\":false}")).isEqualTo("true/false");
    }

    /** FR-011a: no reformatting, localisation, or timezone conversion. */
    @Test
    void aTimestampKeepsItsIsoText() {
        assertThat(resolve("{{at}}", "{\"at\":\"2026-09-19T14:02:31Z\"}")).isEqualTo("2026-09-19T14:02:31Z");
    }

    @Test
    void anIdentifierIsWrittenInFull() {
        String id = "0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b";
        assertThat(resolve("{{id}}", "{\"id\":\"" + id + "\"}")).isEqualTo(id);
    }

    /**
     * research.md §3 — the trap. Jackson's {@code isValueNode()} returns TRUE for {@code NullNode}
     * and {@code NullNode.asText()} returns the string "null", so the naive implementation writes
     * the word "null" into the title. FR-012 and spec.md's "Wildcard resolves to an empty value"
     * edge case both forbid that.
     */
    @Test
    void aJsonNullContributesNothingAndNeverTheWordNull() {
        String title = resolve("Contact {{user.email}}", "{\"user\":{\"email\":null}}");

        assertThat(title).isEqualTo("Contact");
        assertThat(title).doesNotContain("null");
    }

    @Test
    void anObjectContributesNothing() {
        assertThat(resolve("x{{topic}}y", "{\"topic\":{\"name\":\"Robot Arm\"}}")).isEqualTo("xy");
    }

    @Test
    void anArrayContributesNothing() {
        assertThat(resolve("x{{items}}y", "{\"items\":[1,2]}")).isEqualTo("xy");
    }

    @Test
    void aMissingPathContributesNothing() {
        assertThat(resolve("x{{nope.nothing}}y", "{\"topic\":{\"name\":\"Robot Arm\"}}"))
                .isEqualTo("xy");
    }

    /** FR-012: all three blank cases are indistinguishable in the output. */
    @Test
    void missingNullAndStructureAreIndistinguishable() {
        String payload = "{\"a\":null,\"b\":{\"x\":1}}";
        String viaNull = resolve("[{{a}}]", payload);
        String viaStructure = resolve("[{{b}}]", payload);
        String viaMissing = resolve("[{{c}}]", payload);

        assertThat(viaNull).isEqualTo("[]");
        assertThat(viaStructure).isEqualTo("[]");
        assertThat(viaMissing).isEqualTo("[]");
    }

    @Test
    void theEnvelopeEventTypeIsAddressable() {
        assertThat(resolve("{{eventType}}", "{\"eventType\":\"TOPIC_PROPOSED\"}")).isEqualTo("TOPIC_PROPOSED");
    }

    /** FR-009/FR-011: literal text, single braces included, passes through untouched. */
    @Test
    void literalTextIncludingSingleBracesIsUntouched() {
        assertThat(resolve("Set {timeout} for {{topic.name}}", "{\"topic\":{\"name\":\"Robot Arm\"}}"))
                .isEqualTo("Set {timeout} for Robot Arm");
    }

    // ------------------------------------------------------------------- T011 assembly order

    @Test
    void theResolvedTitleIsTrimmed() {
        assertThat(resolve("  {{topic.name}}  ", "{\"topic\":{\"name\":\"Robot Arm\"}}"))
                .isEqualTo("Robot Arm");
    }

    /** FR-014: a blank result falls back to the Rule name and Event Type, so no Task is nameless. */
    @Test
    void aBlankResultFallsBackToTheRuleNameAndEventType() {
        String title = resolve("{{nope}}", "{\"topic\":{\"name\":\"Robot Arm\"}}");

        assertThat(title).contains("Some Rule");
        assertThat(title).contains("TOPIC_PROPOSED");
    }

    @Test
    void aWhitespaceOnlyResultAlsoFallsBack() {
        assertThat(resolve("   {{nope}}   ", "{}")).contains("Some Rule");
    }

    /** FR-015: shortened to 500 characters *including* the ellipsis, never failing creation. */
    @Test
    void anOverLongTitleIsTruncatedToFiveHundredCharactersWithAnEllipsis() {
        String long600 = "x".repeat(600);
        String title = resolve("{{topic.description}}", "{\"topic\":{\"description\":\"" + long600 + "\"}}");

        assertThat(title).hasSize(500);
        assertThat(title).endsWith("…");
    }

    @Test
    void aTitleExactlyAtTheLimitIsNotTruncated() {
        String exact = "y".repeat(500);
        String title = resolve("{{topic.description}}", "{\"topic\":{\"description\":\"" + exact + "\"}}");

        assertThat(title).hasSize(500);
        assertThat(title).doesNotContain("…");
    }

    /** The fallback is applied before truncation, so it can never itself be clipped. */
    @Test
    void theFallbackIsNeverTruncated() {
        String title = resolve("{{nope}}", "{}");

        assertThat(title).doesNotEndWith("…");
        assertThat(title.length()).isLessThan(500);
    }
}

package net.fabcelhaft.hackathonorganiser.task;

import com.fasterxml.jackson.databind.JsonNode;
import net.fabcelhaft.hackathonorganiser.event.EventType;

/**
 * Turns a task title pattern plus an Event's published JSON into the resolved Task title
 * (contracts/title-pattern.md; FR-010 - FR-015).
 *
 * <p>Resolution runs against the <em>already-serialized</em> envelope — byte for byte what a Kafka
 * or HTTP POST Destination receives — rather than the in-memory payload map. That is what makes
 * FR-011a ("exactly as it appears in the Event's data") true by construction: an {@code Instant} is
 * already ISO-8601 text and a {@code UUID} already its canonical string by the time this class sees
 * it, so nothing here re-implements Jackson's value rendering or can drift from what a webhook
 * subscriber sees (research.md §2).
 *
 * <p>Nothing in this class throws. Every failure mode — missing path, JSON null, a structure where a
 * value was expected, a blank result, an over-long result — has a defined non-throwing outcome, so a
 * Task is always creatable (FR-012, FR-014, FR-015).
 */
public final class TitlePatternResolver {

    /** FR-015 — the resolved title's cap, ellipsis included. A deliberate product cap, not a storage one. */
    public static final int MAX_TITLE_LENGTH = 500;

    private static final char ELLIPSIS = '…';

    private TitlePatternResolver() {}

    /**
     * Builds the title in the order research.md §5 fixes: resolve → trim → fall back if blank →
     * truncate. The fallback is applied before truncation so it can never itself be clipped.
     *
     * @param pattern the Rule's unresolved pattern
     * @param envelope the Event's published JSON, parsed
     * @param ruleName the producing Rule's name, used by the FR-014 fallback
     * @param eventType the Event Type that fired, used by the FR-014 fallback
     */
    public static String resolveTitle(String pattern, JsonNode envelope, String ruleName, EventType eventType) {
        StringBuilder rendered = new StringBuilder();
        for (TitlePatternScanner.Segment segment : TitlePatternScanner.scan(pattern)) {
            rendered.append(segment.wildcard() ? resolveValue(envelope, segment.text()) : segment.text());
        }

        String title = rendered.toString().trim();
        if (title.isEmpty()) {
            return fallbackTitle(ruleName, eventType);
        }
        return truncate(title);
    }

    /**
     * Walks {@code path} through {@code root} and renders what it finds
     * (contracts/title-pattern.md "Walking a path" and "What gets written").
     *
     * @return the value's text, or the empty string when the path is absent, addresses a structure,
     *     or addresses a JSON null — FR-012 requires those three cases to be indistinguishable
     */
    public static String resolveValue(JsonNode root, String path) {
        JsonNode node = root;
        for (String segment : path.split("\\.", -1)) {
            if (node == null || segment.isEmpty()) {
                return "";
            }
            if (node.isArray()) {
                // A digit segment selects a position; anything else on an array is missing. Deciding
                // by the *current node* rather than the segment's shape means an object with a
                // numeric-looking key still resolves as a field (research.md §3).
                node = isAllDigits(segment) ? node.get(Integer.parseInt(segment)) : null;
            } else if (node.isObject()) {
                node = node.get(segment);
            } else {
                return "";
            }
        }
        // isValueNode() returns TRUE for NullNode, and NullNode.asText() returns the string "null".
        // Without the isNull() guard a JSON null writes the word "null" into the title, which FR-012
        // and spec.md's "Wildcard resolves to an empty value" edge case both forbid (research.md §3).
        if (node == null || !node.isValueNode() || node.isNull()) {
            return "";
        }
        return node.asText();
    }

    private static boolean isAllDigits(String segment) {
        for (int i = 0; i < segment.length(); i++) {
            if (!Character.isDigit(segment.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** FR-014 — so no Task is ever nameless. */
    private static String fallbackTitle(String ruleName, EventType eventType) {
        return truncate(ruleName + ": " + eventType.name());
    }

    /** FR-015 — the ellipsis counts inside the limit. */
    private static String truncate(String title) {
        if (title.length() <= MAX_TITLE_LENGTH) {
            return title;
        }
        return title.substring(0, MAX_TITLE_LENGTH - 1) + ELLIPSIS;
    }
}

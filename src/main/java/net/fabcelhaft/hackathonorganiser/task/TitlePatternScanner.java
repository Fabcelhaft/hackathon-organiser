package net.fabcelhaft.hackathonorganiser.task;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a task title pattern into literal text and wildcards (contracts/title-pattern.md "Grammar";
 * FR-007, FR-009, FR-010).
 *
 * <p>One left-to-right scan serves both validation and rendering, so the two can never disagree
 * about what counts as a wildcard — a pattern that saved cleanly can never surprise the resolver
 * later (research.md §4).
 *
 * <p>A regular expression is deliberately <em>not</em> used. A pattern such as {@code
 * \{\{([^{}]*)\}\}} finds well-formed wildcards but is structurally unable to report an
 * <em>unclosed</em> one: it simply does not match, and the malformed text passes through as literal
 * — exactly the silent failure FR-007 exists to prevent.
 */
public final class TitlePatternScanner {

    private static final String OPEN = "{{";
    private static final String CLOSE = "}}";

    private TitlePatternScanner() {}

    /**
     * One piece of a scanned pattern.
     *
     * @param wildcard true when {@code text} is a wildcard's path, false when it is literal text
     * @param text the path (without braces) for a wildcard, or the literal text
     */
    public record Segment(boolean wildcard, String text) {}

    /**
     * Splits {@code pattern} into its literal and wildcard pieces. Assumes {@code pattern} already
     * passed {@link #validationError(String)}; an unclosed wildcard is treated as literal text here
     * rather than throwing, so rendering can never fail (FR-012's "never prevent the Task from being
     * created" extends to malformed input that somehow reached this point).
     */
    public static List<Segment> scan(String pattern) {
        List<Segment> segments = new ArrayList<>();
        if (pattern == null) {
            return segments;
        }
        int cursor = 0;
        while (cursor < pattern.length()) {
            int open = pattern.indexOf(OPEN, cursor);
            if (open < 0) {
                addLiteral(segments, pattern.substring(cursor));
                break;
            }
            int close = pattern.indexOf(CLOSE, open + OPEN.length());
            if (close < 0) {
                // Unclosed: the remainder is literal. validationError() rejects this at save time.
                addLiteral(segments, pattern.substring(cursor));
                break;
            }
            addLiteral(segments, pattern.substring(cursor, open));
            segments.add(new Segment(true, pattern.substring(open + OPEN.length(), close)));
            cursor = close + CLOSE.length();
        }
        return segments;
    }

    private static void addLiteral(List<Segment> segments, String text) {
        if (!text.isEmpty()) {
            segments.add(new Segment(false, text));
        }
    }

    /**
     * Checks {@code pattern} against contracts/title-pattern.md's "Validation, at save time" table
     * (FR-007).
     *
     * <p>Paths are deliberately <em>not</em> checked against the Event catalog: FR-012 makes an
     * unmatched wildcard resolve to nothing, which is what lets one Rule serve several Event Types
     * whose data differs.
     *
     * @return a message naming the offending wildcard, or {@code null} when the pattern is valid
     */
    public static String validationError(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return "A task title pattern is required for a Task Rule";
        }
        int cursor = 0;
        while (cursor < pattern.length()) {
            int open = pattern.indexOf(OPEN, cursor);
            if (open < 0) {
                return null;
            }
            int close = pattern.indexOf(CLOSE, open + OPEN.length());
            if (close < 0) {
                return "Unclosed wildcard in the task title pattern: '"
                        + pattern.substring(open)
                        + "' is missing its closing }}";
            }
            String path = pattern.substring(open + OPEN.length(), close);
            String pathError = pathError(path);
            if (pathError != null) {
                return pathError;
            }
            cursor = close + CLOSE.length();
        }
        return null;
    }

    private static String pathError(String path) {
        if (path.isEmpty()) {
            return "Empty wildcard in the task title pattern: '{{}}' has no path";
        }
        for (String segment : path.split("\\.", -1)) {
            if (segment.isEmpty()) {
                return "Invalid wildcard '{{" + path + "}}': a path segment is empty — check for a "
                        + "leading, trailing, or doubled dot";
            }
        }
        return null;
    }
}

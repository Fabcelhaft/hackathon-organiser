package net.fabcelhaft.hackathonorganiser.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MarkdownRenderer} (T035): headings, lists, links, emphasis render
 * correctly (FR-017); a submitted {@code <script>} tag / {@code on*} attribute is stripped
 * (FR-022); an {@code <img>} with {@code alt} renders inline (FR-026); a markdown {@code #}
 * heading renders as {@code <h2>} and {@code ######} caps at {@code <h6>} (FR-036, research.md
 * §1). A plain synchronous {@code String -> String} function with no reactive chain — ordinary
 * JUnit assertions, not {@code StepVerifier} (Constitution Development Workflow #4 exempts it).
 */
class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void rendersListsLinksAndEmphasis() {
        String html = renderer.render("- one\n- two\n\n[link](https://example.com) and **bold** and *em*");

        assertThat(html).contains("<ul>").contains("<li>one</li>").contains("<li>two</li>");
        assertThat(html).contains("<a href=\"https://example.com\"").contains(">link</a>");
        assertThat(html).contains("<strong>bold</strong>");
        assertThat(html).contains("<em>em</em>");
    }

    @Test
    void topLevelHeadingRendersAsH2() {
        String html = renderer.render("# Title");

        assertThat(html).contains("<h2>Title</h2>");
        assertThat(html).doesNotContain("<h1>");
    }

    @Test
    void aSixthLevelHeadingCapsAtH6RatherThanOverflowing() {
        String html = renderer.render("###### Deepest");

        assertThat(html).contains("<h6>Deepest</h6>");
        assertThat(html).doesNotContain("<h7>");
    }

    @Test
    void stripsAScriptTag() {
        String html = renderer.render("Hello<script>alert('xss')</script>World");

        assertThat(html).doesNotContain("<script");
        assertThat(html).doesNotContain("alert(");
    }

    @Test
    void stripsAnOnAttributeFromAnAllowedElement() {
        String html = renderer.render("<a href=\"https://example.com\" onclick=\"alert(1)\">link</a>");

        assertThat(html).doesNotContain("onclick");
    }

    @Test
    void anImageWithAltRendersInline() {
        String html = renderer.render("![a description](https://example.com/pic.png)");

        assertThat(html).contains("<img src=\"https://example.com/pic.png\" alt=\"a description\"");
    }

    // --- Feature 010 (T002): autolinking + new-tab links --------------------------------------
    // These apply to every caller of this renderer, so Content Pages gain them alongside Topic
    // descriptions (FR-004b) — a deliberate, clarified change, asserted here rather than left
    // to be discovered.

    @Test
    void aBareWebAddressBecomesALinkWhoseTextIsTheAddress() {
        String html = renderer.render("See https://example.org/docs for details");

        assertThat(html).contains("href=\"https://example.org/docs\"");
        assertThat(html).contains(">https://example.org/docs</a>");
    }

    @Test
    void aTrailingFullStopIsNotSwallowedIntoABareLink() {
        String html = renderer.render("Read https://example.org/docs.");

        assertThat(html).contains("href=\"https://example.org/docs\"");
        assertThat(html).doesNotContain("href=\"https://example.org/docs.\"");
        // The full stop survives as text immediately after the link.
        assertThat(html).contains("</a>.");
    }

    @Test
    void balancedParenthesesInsideABareLinkAreKeptButAWrappingOneIsNot() {
        String html = renderer.render("(see https://example.org/a(b)c)");

        // The sanitizer percent-encodes parentheses in the href, so the meaningful assertions are
        // on the link text and on where the wrapping ')' lands — outside the anchor, as prose.
        assertThat(html).contains(">https://example.org/a(b)c</a>");
        assertThat(html).contains("</a>)");
        assertThat(html).contains("a%28b%29c").doesNotContain("a%28b%29c%29");
    }

    /**
     * Pins library behaviour rather than a requirement: {@code AutolinkExtension} links emails as
     * well as web addresses and offers no switch to separate the two. FR-002 only asks for web
     * addresses and forbids nothing here, so this is accepted — asserted so a future library change
     * is noticed rather than silently altering what participants' descriptions render as.
     */
    @Test
    void anEmailAddressIsAlsoAutolinked() {
        String html = renderer.render("Write to someone@example.org please");

        // The sanitizer HTML-encodes '@' in the href, so match the encoded form.
        assertThat(html).contains("href=\"mailto:someone&#64;example.org\"");
        assertThat(html).contains("target=\"_blank\"");
    }

    @Test
    void everyRenderedLinkOpensInANewTabWithProtectiveRelTokens() {
        String html = renderer.render("[explicit](https://example.com) and bare https://example.org/x");

        // Two links: one explicit markdown link, one autolinked bare address. Both must carry the
        // same protection — rel is required by the sanitizer policy, not by what the author wrote.
        assertThat(html).containsSubsequence("<a ", "</a>", "<a ", "</a>");
        assertThat(countOccurrences(html, "target=\"_blank\"")).isEqualTo(2);
        assertThat(countOccurrences(html, "rel=\"")).isEqualTo(2);
        assertThat(html).contains("nofollow").contains("noopener").contains("noreferrer");
    }

    @Test
    void aJavascriptUrlIsStripped() {
        String html = renderer.render("[click](javascript:alert(1))");

        assertThat(html).doesNotContain("javascript:");
    }

    @Test
    void anOnErrorAttributeOnAnImageIsStripped() {
        String html = renderer.render("<img src=\"https://example.com/p.png\" onerror=\"alert(1)\">");

        assertThat(html).doesNotContain("onerror");
    }

    @Test
    void blockElementsStillRenderAfterThePolicyRebuild() {
        String html = renderer.render("> quoted\n\n1. first\n2. second\n\n`inline code`");

        assertThat(html).contains("<blockquote>");
        assertThat(html).contains("<ol>").contains("<li>first</li>");
        assertThat(html).contains("<code>inline code</code>");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}

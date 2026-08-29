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
}

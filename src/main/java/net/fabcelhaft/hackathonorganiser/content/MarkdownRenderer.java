package net.fabcelhaft.hackathonorganiser.content;

import java.util.Collections;
import java.util.Set;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import org.springframework.stereotype.Component;

/**
 * The single point in this codebase that ever emits unescaped HTML (research.md §1): parses
 * Organiser-authored markdown with commonmark-java, then sanitizes the resulting HTML with the
 * OWASP allowlist sanitizer before it is ever placed into a Thymeleaf model via {@code th:utext}.
 * Every other template continues using Thymeleaf's default-escaped {@code th:text}.
 *
 * <p>FR-036: a markdown {@code #} heading renders one level below the page's own top-level
 * heading — {@code min(level + 1, 6)}, capped at HTML's own {@code <h6>} ceiling — via a custom
 * {@link org.commonmark.renderer.html.HtmlNodeRendererFactory} that intercepts {@link Heading}
 * nodes, so every rendered page keeps exactly one logical top-level heading (the Thymeleaf-
 * rendered page/section title) regardless of what the Organiser writes in the markdown body.
 *
 * <p>FR-022: the OWASP sanitizer policy ({@code Sanitizers.FORMATTING.and(LINKS).and(IMAGES)})
 * strips {@code <script>} tags and {@code on*} attributes — an allowlist approach, not hand-rolled
 * regex stripping, per this codebase's security-sensitive-parsing guidance.
 */
@Component
public class MarkdownRenderer {

    // Sanitizers.BLOCKS is what actually allows headings/paragraphs/lists/blockquotes through —
    // FORMATTING alone only covers inline emphasis elements (b/i/em/strong etc.), which would
    // otherwise strip every heading and list commonmark produces (FR-017, FR-036).
    private static final PolicyFactory POLICY = Sanitizers.BLOCKS
            .and(Sanitizers.FORMATTING)
            .and(Sanitizers.LINKS)
            .and(Sanitizers.IMAGES);

    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer =
            HtmlRenderer.builder().nodeRendererFactory(HeadingLevelShiftingRenderer::new).build();

    /** Parses {@code markdown} and returns sanitized, heading-shifted HTML ready for {@code th:utext}. */
    public String render(String markdown) {
        Node document = parser.parse(markdown == null ? "" : markdown);
        String html = renderer.render(document);
        return POLICY.sanitize(html);
    }

    private static final class HeadingLevelShiftingRenderer implements NodeRenderer {

        private final HtmlNodeRendererContext context;
        private final HtmlWriter html;

        HeadingLevelShiftingRenderer(HtmlNodeRendererContext context) {
            this.context = context;
            this.html = context.getWriter();
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Heading.class);
        }

        @Override
        public void render(Node node) {
            Heading heading = (Heading) node;
            String tag = "h" + Math.min(heading.getLevel() + 1, 6);
            html.line();
            html.tag(tag, context.extendAttributes(node, tag, Collections.<String, String>emptyMap()));
            renderChildren(node);
            html.tag("/" + tag);
            html.line();
        }

        private void renderChildren(Node parent) {
            Node node = parent.getFirstChild();
            while (node != null) {
                Node next = node.getNext();
                context.render(node);
                node = next;
            }
        }
    }
}

package net.fabcelhaft.hackathonorganiser.content;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.node.Heading;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;
import org.owasp.html.HtmlPolicyBuilder;
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
 * <p>FR-022: the OWASP sanitizer policy strips {@code <script>} tags and {@code on*} attributes —
 * an allowlist approach, not hand-rolled regex stripping, per this codebase's
 * security-sensitive-parsing guidance.
 *
 * <p><b>Feature 010 (research.md §1, §2):</b> two changes here apply to <em>every</em> caller, so
 * Topic descriptions and Content Pages behave identically (010 FR-004b — a deliberate, clarified
 * change, not a side effect):
 *
 * <ul>
 *   <li><b>Bare-URL autolinking</b> via {@link AutolinkExtension}: a web address typed plainly,
 *       without markdown link syntax, becomes a link (010 FR-002). The extension also links email
 *       addresses as {@code mailto:} and exposes no way to disable that half; since 010 FR-002 only
 *       requires web addresses and says nothing against emails, the library's behaviour is accepted
 *       rather than worked around.
 *   <li><b>New-tab links</b>: {@link NewTabLinkAttributeProvider} puts {@code target="_blank"} on
 *       every link, and the policy below forces {@code rel="nofollow noopener noreferrer"} onto it,
 *       so a destination page can never reach back through {@code window.opener} (010 FR-004).
 * </ul>
 */
@Component
public class MarkdownRenderer {

    private static final Pattern BLANK_TARGET = Pattern.compile("_blank");

    // Replaces Sanitizers.LINKS, which allows only href on <a> and would therefore strip the
    // target attribute NewTabLinkAttributeProvider adds. requireRelsOnLinks is what guarantees the
    // protective rel tokens regardless of what the author wrote — including nofollow, which
    // Sanitizers.LINKS used to contribute. Restricting target to the literal _blank stops an
    // author naming a frame.
    private static final PolicyFactory LINKS_IN_NEW_TAB = new HtmlPolicyBuilder()
            .allowStandardUrlProtocols()
            .allowElements("a")
            .allowAttributes("href")
            .onElements("a")
            .allowAttributes("target")
            .matching(BLANK_TARGET)
            .onElements("a")
            .requireRelsOnLinks("nofollow", "noopener", "noreferrer")
            .toFactory();

    // Sanitizers.BLOCKS is what actually allows headings/paragraphs/lists/blockquotes through —
    // FORMATTING alone only covers inline emphasis elements (b/i/em/strong etc.), which would
    // otherwise strip every heading and list commonmark produces (FR-017, FR-036).
    private static final PolicyFactory POLICY = Sanitizers.BLOCKS
            .and(Sanitizers.FORMATTING)
            .and(LINKS_IN_NEW_TAB)
            .and(Sanitizers.IMAGES);

    private final Parser parser =
            Parser.builder().extensions(List.of(AutolinkExtension.create())).build();
    private final HtmlRenderer renderer = HtmlRenderer.builder()
            .nodeRendererFactory(HeadingLevelShiftingRenderer::new)
            .attributeProviderFactory(context -> new NewTabLinkAttributeProvider())
            .build();

    /** Parses {@code markdown} and returns sanitized, heading-shifted HTML ready for {@code th:utext}. */
    public String render(String markdown) {
        Node document = parser.parse(markdown == null ? "" : markdown);
        String html = renderer.render(document);
        return POLICY.sanitize(html);
    }

    /**
     * Adds {@code target="_blank"} to every rendered link — both explicit markdown links and the
     * bare addresses {@link AutolinkExtension} turns into {@link Link} nodes, since both arrive
     * here as the same node type. The matching {@code rel} tokens are not set here: they are forced
     * by {@link #LINKS_IN_NEW_TAB} at sanitization time, which holds even for raw {@code <a>} tags
     * that never passed through this provider (010 FR-004).
     */
    private static final class NewTabLinkAttributeProvider implements AttributeProvider {

        @Override
        public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
            if (node instanceof Link) {
                attributes.put("target", "_blank");
            }
        }
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

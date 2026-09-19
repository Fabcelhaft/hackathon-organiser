package net.fabcelhaft.hackathonorganiser.topic;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The allowlist of file types accepted as Topic Attachments (010 FR-016a, research.md §5):
 * documents, plain text and markdown, images, and ZIP archives. Code, datasets and notebooks are
 * shared inside a ZIP.
 *
 * <p>A file is accepted only when its <em>extension</em> is listed here <em>and</em> the
 * {@code Content-Type} the browser declared is one this extension maps to. Requiring both is what
 * the clarification asked for: it stops an author accidentally uploading the wrong thing and stops
 * casual renaming. It is deliberately <b>not</b> a defence against a determined attacker — browsers
 * derive the declared type from the extension, so the two agree by construction for a hand-crafted
 * request. Content sniffing is out of scope (spec Assumptions); safety at read time comes from
 * every download being served as an attachment rather than rendered inline (FR-019).
 *
 * <p>Several extensions map to more than one type because that is what browsers actually send:
 * Windows reports ZIP as {@code application/x-zip-compressed}, and {@code .md} very often arrives
 * as {@code text/plain}. A blank or {@code application/octet-stream} declaration is rejected rather
 * than waved through, since it carries no information to cross-check.
 */
public final class TopicAttachmentType {

    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
            Map.entry("pdf", Set.of("application/pdf")),
            Map.entry("doc", Set.of("application/msword")),
            Map.entry(
                    "docx",
                    Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
            Map.entry("xls", Set.of("application/vnd.ms-excel")),
            Map.entry(
                    "xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
            Map.entry("ppt", Set.of("application/vnd.ms-powerpoint")),
            Map.entry(
                    "pptx",
                    Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation")),
            Map.entry("odt", Set.of("application/vnd.oasis.opendocument.text")),
            Map.entry("ods", Set.of("application/vnd.oasis.opendocument.spreadsheet")),
            Map.entry("odp", Set.of("application/vnd.oasis.opendocument.presentation")),
            Map.entry("txt", Set.of("text/plain")),
            Map.entry("md", Set.of("text/markdown", "text/x-markdown", "text/plain")),
            Map.entry("markdown", Set.of("text/markdown", "text/x-markdown", "text/plain")),
            Map.entry("png", Set.of("image/png")),
            Map.entry("jpg", Set.of("image/jpeg")),
            Map.entry("jpeg", Set.of("image/jpeg")),
            Map.entry("gif", Set.of("image/gif")),
            Map.entry("webp", Set.of("image/webp")),
            Map.entry("zip", Set.of("application/zip", "application/x-zip-compressed")));

    /** The message shown when an upload is rejected — lists what is accepted, per FR-016a. */
    public static final String REJECTION_MESSAGE = "Only PDF, Word, Excel, PowerPoint, OpenDocument, "
            + "text, markdown, PNG/JPEG/GIF/WebP images and ZIP files are allowed";

    private TopicAttachmentType() {}

    /**
     * Whether this file name's extension and declared content type are both acceptable and agree
     * with each other.
     */
    public static boolean isAllowed(String fileName, String contentType) {
        Set<String> typesForExtension = ALLOWED.get(extensionOf(fileName));
        if (typesForExtension == null) {
            return false;
        }
        return typesForExtension.contains(normalizeContentType(contentType));
    }

    /**
     * The lower-cased extension of {@code fileName} without its dot, or an empty string when the
     * name has no extension (which is never allowed).
     */
    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * The bare media type, lower-cased, with any parameters dropped — a browser may send
     * {@code text/plain; charset=UTF-8}, which must compare equal to {@code text/plain}. Returns an
     * empty string for a null, blank or {@code application/octet-stream} declaration, none of which
     * can be cross-checked against an extension.
     */
    static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String bare = (semicolon < 0 ? contentType : contentType.substring(0, semicolon))
                .trim()
                .toLowerCase(Locale.ROOT);
        return "application/octet-stream".equals(bare) ? "" : bare;
    }
}

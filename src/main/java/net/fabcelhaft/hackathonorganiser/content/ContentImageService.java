package net.fabcelhaft.hackathonorganiser.content;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Upload/manage operations on {@link ContentImage} (T057; FR-024–FR-029). Format/size validation
 * (FR-029) and the required {@code alt_text} (FR-025a) are checked before any write. Deletion is
 * blocked while an image is still referenced by one or more Content Pages (FR-028), detected via a
 * query-time substring search over {@code content_pages.body_markdown} rather than a join table
 * (research.md §3) — the embed reference is always the literal {@code /content-images/{id}} path,
 * so a substring search is exact and needs no bookkeeping kept in sync on every Content Page save.
 */
@Service
public class ContentImageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    private static final int MAX_BYTES = 5 * 1024 * 1024;

    private final ContentImageRepository contentImageRepository;
    private final DatabaseClient databaseClient;

    public ContentImageService(ContentImageRepository contentImageRepository, DatabaseClient databaseClient) {
        this.contentImageRepository = contentImageRepository;
        this.databaseClient = databaseClient;
    }

    public Flux<ContentImage> findAll() {
        return contentImageRepository.findAll();
    }

    public Mono<ContentImage> findById(UUID id) {
        return contentImageRepository.findById(id);
    }

    /**
     * Uploads a new Content Image, rejecting a non-PNG/JPEG/GIF/WebP content type or a size over
     * 5 MB (FR-029) or a blank {@code alt_text} (FR-025a) with a {@link ContentImageConflictException}
     * — validated before any write, so a rejected upload is never partially stored.
     */
    public Mono<ContentImage> upload(byte[] data, String contentType, String altText) {
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            return Mono.error(
                    new ContentImageConflictException("Only PNG, JPEG, GIF, and WebP images are allowed"));
        }
        if (data == null || data.length > MAX_BYTES) {
            return Mono.error(new ContentImageConflictException("Images must be 5 MB or smaller"));
        }
        if (isBlank(altText)) {
            return Mono.error(new ContentImageConflictException("Alt text is required"));
        }
        ContentImage image = new ContentImage();
        image.setAltText(altText);
        image.setContentType(contentType);
        image.setByteSize(data.length);
        image.setData(data);
        Instant now = Instant.now();
        image.setCreatedAt(now);
        image.setUpdatedAt(now);
        return contentImageRepository.save(image);
    }

    /**
     * Edits an existing image's alt text in place (FR-025b) — future copies of the embed syntax
     * reflect the new text; already-pasted markdown is untouched (this method never rewrites
     * {@code content_pages.body_markdown}). Rejects a blank {@code alt_text}. Completes empty if no
     * Content Image exists with the given id.
     */
    public Mono<ContentImage> updateAltText(UUID id, String altText) {
        if (isBlank(altText)) {
            return Mono.error(new ContentImageConflictException("Alt text is required"));
        }
        return contentImageRepository.findById(id).flatMap(image -> {
            image.setAltText(altText);
            image.setUpdatedAt(Instant.now());
            return contentImageRepository.save(image);
        });
    }

    /**
     * Deletes a Content Image, rejecting with a {@link ContentImageConflictException} naming every
     * still-referencing Content Page's title if any exist (FR-028). Completes empty if no Content
     * Image exists with the given id.
     */
    public Mono<ContentImage> delete(UUID id) {
        return contentImageRepository.findById(id).flatMap(image -> referencingPageTitles(id).flatMap(titles -> {
            if (!titles.isEmpty()) {
                return Mono.<ContentImage>error(new ContentImageConflictException(
                        "This image is still used by: " + String.join(", ", titles)));
            }
            return contentImageRepository.deleteById(id).thenReturn(image);
        }));
    }

    private Mono<List<String>> referencingPageTitles(UUID imageId) {
        String needle = "/content-images/" + imageId;
        return databaseClient
                .sql("SELECT title FROM content_pages WHERE body_markdown LIKE '%' || :needle || '%'")
                .bind("needle", needle)
                .mapValue(String.class)
                .all()
                .collectList();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

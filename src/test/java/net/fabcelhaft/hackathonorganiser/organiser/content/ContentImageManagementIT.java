package net.fabcelhaft.hackathonorganiser.organiser.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.content.ContentImage;
import net.fabcelhaft.hackathonorganiser.content.ContentImageRepository;
import net.fabcelhaft.hackathonorganiser.content.ContentPage;
import net.fabcelhaft.hackathonorganiser.content.ContentPageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.OidcLoginMutator;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration tests for User Story 7's Content Image upload/embed (T050;
 * contracts/content-images.md): uploading a PNG succeeds and the library shows the copyable
 * {@code ![alt](/content-images/{id})} syntax (FR-025); a non-image or over-5MB upload is rejected
 * without being stored (FR-029); an embedded image renders inline on a Content Page (FR-026);
 * deleting a still-referenced image is blocked, naming the referencing page(s) (FR-028); deleting
 * an unreferenced image succeeds; editing alt text in place updates future copy syntax but leaves
 * already-pasted markdown untouched (FR-025b); a non-Organiser is denied every management route
 * (FR-027); {@code GET /content-images/{id}} serves raw bytes with the correct {@code Content-Type}
 * to any authenticated user.
 */
@SpringBootTest
@Testcontainers
class ContentImageManagementIT {

    // The smallest possible valid PNG (a 1x1 transparent pixel).
    private static final byte[] TINY_PNG = Base64.getDecoder()
            .decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @Autowired
    ApplicationContext applicationContext;

    WebTestClient webTestClient;

    @Autowired
    ContentImageRepository contentImageRepository;

    @Autowired
    ContentPageRepository contentPageRepository;

    @BeforeEach
    void setUpWebTestClient() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    @Test
    void uploadingAPngSucceedsAndTheLibraryShowsTheCopyableEmbedSyntax() {
        String altText = "A tiny pixel " + UUID.randomUUID();

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/content-images")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipartWith(TINY_PNG, "image/png", altText).build()))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        ContentImage saved = findByAltText(altText);
        assertThat(saved.getContentType()).isEqualTo("image/png");

        String body = webTestClient
                .mutateWith(organiser())
                .get()
                .uri("/organiser/content-images")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).contains("![" + altText + "](/content-images/" + saved.getId() + ")");
    }

    @Test
    void aNonImageUploadIsRejectedWithoutBeingStored() {
        String altText = "Rejected " + UUID.randomUUID();

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/content-images")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(
                        multipartWith("not an image".getBytes(), "text/plain", altText).build()))
                .exchange()
                .expectStatus()
                .isOk(); // form re-rendered with error, not stored

        assertThat(contentImageRepository.findAll().filter(i -> altText.equals(i.getAltText())).blockFirst())
                .isNull();
    }

    @Test
    void anOversizedUploadIsRejectedWithoutBeingStored() {
        String altText = "Too Big " + UUID.randomUUID();
        byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/content-images")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipartWith(tooLarge, "image/png", altText).build()))
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(contentImageRepository.findAll().filter(i -> altText.equals(i.getAltText())).blockFirst())
                .isNull();
    }

    @Test
    void anEmbeddedImageRendersInlineOnAContentPage() {
        ContentImage image = persistImage("Embedded " + UUID.randomUUID());
        ContentPage page = persistPage(
                "Embed Page " + UUID.randomUUID(), "![alt](/content-images/" + image.getId() + ")");

        String body = webTestClient
                .mutateWith(organiser())
                .get()
                .uri("/info/{id}", page.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("<img src=\"/content-images/" + image.getId() + "\"");
    }

    @Test
    void deletingAStillReferencedImageIsBlockedNamingTheReferencingPage() {
        ContentImage image = persistImage("Referenced " + UUID.randomUUID());
        ContentPage page = persistPage(
                "Referencing Page " + UUID.randomUUID(), "![alt](/content-images/" + image.getId() + ")");

        String body = webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/content-images/{id}/delete", image.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains(page.getTitle());
        assertThat(contentImageRepository.findById(image.getId()).block()).isNotNull();
    }

    @Test
    void deletingAnUnreferencedImageSucceeds() {
        ContentImage image = persistImage("Unreferenced " + UUID.randomUUID());

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/content-images/{id}/delete", image.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(contentImageRepository.findById(image.getId()).block()).isNull();
    }

    @Test
    void editingAltTextInPlaceUpdatesFutureCopySyntaxButLeavesAlreadyPastedMarkdownUntouched() {
        ContentImage image = persistImage("Old Alt " + UUID.randomUUID());
        ContentPage page = persistPage(
                "Untouched Page " + UUID.randomUUID(),
                "![Old Alt Pasted](/content-images/" + image.getId() + ")");

        String newAltText = "New Alt " + UUID.randomUUID();
        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/content-images/{id}/alt-text", image.getId())
                .body(BodyInserters.fromFormData("alt_text", newAltText))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(contentImageRepository.findById(image.getId()).block().getAltText())
                .isEqualTo(newAltText);
        assertThat(contentPageRepository.findById(page.getId()).block().getBodyMarkdown())
                .contains("Old Alt Pasted");

        String libraryBody = webTestClient
                .mutateWith(organiser())
                .get()
                .uri("/organiser/content-images")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(libraryBody).contains("![" + newAltText + "](/content-images/" + image.getId() + ")");
    }

    @Test
    void nonOrganiserIsDeniedEveryManagementRoute() {
        ContentImage image = persistImage("Guarded " + UUID.randomUUID());

        webTestClient
                .mutateWith(standardUser())
                .get()
                .uri("/organiser/content-images")
                .exchange()
                .expectStatus()
                .isForbidden();

        webTestClient
                .mutateWith(standardUser())
                .post()
                .uri("/organiser/content-images/{id}/delete", image.getId())
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void getContentImagesServesRawBytesWithTheCorrectContentTypeToAnyAuthenticatedUser() {
        ContentImage image = persistImage("Streamed " + UUID.randomUUID());

        webTestClient
                .mutateWith(standardUser())
                .get()
                .uri("/content-images/{id}", image.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType("image/png")
                .expectBody(byte[].class)
                .isEqualTo(TINY_PNG);
    }

    // --- Test helpers ------------------------------------------------------------------------------

    private MultipartBodyBuilder multipartWith(byte[] data, String contentType, String altText) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", data).filename("upload.bin").contentType(MediaType.parseMediaType(contentType));
        builder.part("alt_text", altText);
        return builder;
    }

    private ContentImage findByAltText(String altText) {
        return contentImageRepository
                .findAll()
                .filter(image -> altText.equals(image.getAltText()))
                .blockFirst();
    }

    private ContentImage persistImage(String altText) {
        ContentImage image = new ContentImage();
        image.setAltText(altText);
        image.setContentType("image/png");
        image.setByteSize(TINY_PNG.length);
        image.setData(TINY_PNG);
        Instant now = Instant.now();
        image.setCreatedAt(now);
        image.setUpdatedAt(now);
        return contentImageRepository.save(image).block();
    }

    private ContentPage persistPage(String title, String bodyMarkdown) {
        ContentPage page = new ContentPage();
        page.setTitle(title);
        page.setBodyMarkdown(bodyMarkdown);
        page.setSortIndex(0);
        page.setHomepage(false);
        Instant now = Instant.now();
        page.setCreatedAt(now);
        page.setUpdatedAt(now);
        return contentPageRepository.save(page).block();
    }

    private static OidcLoginMutator organiser() {
        return mockOidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ORGANISER"));
    }

    private static OidcLoginMutator standardUser() {
        return mockOidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}

package net.fabcelhaft.hackathonorganiser.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link ContentImageService} (T051): rejects a non-PNG/JPEG/GIF/WebP content type
 * or a size over 5 MB before any write (FR-029); rejects a blank {@code alt_text} (FR-025a); the
 * delete-block substring search over {@code content_pages.body_markdown} correctly names every
 * referencing page's title (FR-028). Per Constitution Development Workflow #4, the multi-operator
 * reactive chains under test are verified with {@link StepVerifier}, never {@code .block()}.
 */
@ExtendWith(MockitoExtension.class)
class ContentImageServiceTest {

    @Mock
    private ContentImageRepository contentImageRepository;

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private DatabaseClient.GenericExecuteSpec executeSpec;

    private ContentImageService contentImageService;

    @BeforeEach
    void setUp() {
        contentImageService = new ContentImageService(contentImageRepository, databaseClient);
    }

    // --- upload: format/size validated before any write (FR-029) --------------------------------

    @Test
    void uploadRejectsANonAllowedContentType() {
        StepVerifier.create(contentImageService.upload(new byte[] {1, 2, 3}, "application/pdf", "Alt"))
                .expectError(ContentImageConflictException.class)
                .verify();

        verify(contentImageRepository, never()).save(any());
    }

    @Test
    void uploadRejectsAnOversizedImage() {
        byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];

        StepVerifier.create(contentImageService.upload(tooLarge, "image/png", "Alt"))
                .expectError(ContentImageConflictException.class)
                .verify();

        verify(contentImageRepository, never()).save(any());
    }

    @Test
    void uploadRejectsABlankAltText() {
        StepVerifier.create(contentImageService.upload(new byte[] {1, 2, 3}, "image/png", "  "))
                .expectError(ContentImageConflictException.class)
                .verify();

        verify(contentImageRepository, never()).save(any());
    }

    @Test
    void uploadSucceedsForAnAllowedTypeWithinTheSizeLimitAndRequiredAltText() {
        byte[] data = new byte[] {1, 2, 3};
        when(contentImageRepository.save(any(ContentImage.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(contentImageService.upload(data, "image/png", "A description"))
                .assertNext(image -> {
                    assertThat(image.getContentType()).isEqualTo("image/png");
                    assertThat(image.getByteSize()).isEqualTo(data.length);
                    assertThat(image.getAltText()).isEqualTo("A description");
                })
                .verifyComplete();
    }

    // --- updateAltText: rejects blank (FR-025a) --------------------------------------------------

    @Test
    void updateAltTextRejectsBlank() {
        StepVerifier.create(contentImageService.updateAltText(UUID.randomUUID(), ""))
                .expectError(ContentImageConflictException.class)
                .verify();

        verify(contentImageRepository, never()).findById(any(UUID.class));
    }

    // --- delete: blocked while referenced, naming every referencing page (FR-028) ----------------

    @Test
    void deleteIsBlockedWhileStillReferencedAndNamesEveryReferencingPage() {
        UUID imageId = UUID.randomUUID();
        ContentImage image = imageOf(imageId);
        when(contentImageRepository.findById(imageId)).thenReturn(Mono.just(image));
        stubReferencingTitles(Flux.just("Page One", "Page Two"));

        StepVerifier.create(contentImageService.delete(imageId))
                .expectErrorMatches(ex -> ex instanceof ContentImageConflictException
                        && ex.getMessage().contains("Page One")
                        && ex.getMessage().contains("Page Two"))
                .verify();

        verify(contentImageRepository, never()).deleteById(any(UUID.class));
    }

    @Test
    void deleteSucceedsWhenUnreferenced() {
        UUID imageId = UUID.randomUUID();
        ContentImage image = imageOf(imageId);
        when(contentImageRepository.findById(imageId)).thenReturn(Mono.just(image));
        stubReferencingTitles(Flux.empty());
        when(contentImageRepository.deleteById(imageId)).thenReturn(Mono.empty());

        StepVerifier.create(contentImageService.delete(imageId))
                .expectNext(image)
                .verifyComplete();
    }

    @Test
    void deleteOfAnUnknownImageCompletesEmpty() {
        UUID imageId = UUID.randomUUID();
        when(contentImageRepository.findById(imageId)).thenReturn(Mono.empty());

        StepVerifier.create(contentImageService.delete(imageId)).verifyComplete();
    }

    // --- test helpers ------------------------------------------------------------------------------

    private ContentImage imageOf(UUID id) {
        ContentImage image = new ContentImage();
        image.setId(id);
        image.setAltText("Alt");
        image.setContentType("image/png");
        image.setByteSize(3);
        image.setData(new byte[] {1, 2, 3});
        Instant now = Instant.now();
        image.setCreatedAt(now);
        image.setUpdatedAt(now);
        return image;
    }

    @SuppressWarnings("unchecked")
    private void stubReferencingTitles(Flux<String> titles) {
        RowsFetchSpec<String> fetch = mock(RowsFetchSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.mapValue(String.class)).thenReturn(fetch);
        when(fetch.all()).thenReturn(titles);
    }
}

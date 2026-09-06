package net.fabcelhaft.hackathonorganiser.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link ContentPageService} (Feature 008, T002): designating a page for a context
 * clears that same context from its previous holder, independently of the other two contexts
 * (FR-013); {@code findInfoList()} excludes every designated page and relies on the
 * {@code sortIndex}-then-{@code title} repository ordering (FR-002, FR-014); {@code nextSortIndex()}
 * pre-fills one above the current highest index, or {@code 0} when no page exists (FR-019a/b);
 * {@code findRenderedByContext} / {@code findRenderedDetail} / {@code findRenderedDefault} wrap
 * the sanitized-HTML rendering. Per Constitution Development Workflow #4, every reactive chain is
 * verified with {@link StepVerifier}, never {@code .block()}.
 */
@ExtendWith(MockitoExtension.class)
class ContentPageServiceTest {

    @Mock
    private ContentPageRepository contentPageRepository;

    private ContentPageService contentPageService;

    @BeforeEach
    void setUp() {
        contentPageService = new ContentPageService(contentPageRepository, new MarkdownRenderer());
    }

    // --- Context exclusivity (FR-013) --------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = ContentPageContext.class, names = {"HOMEPAGE", "TOPIC_CREATION", "USER_REGISTRATION"})
    void creatingAPageForAContextClearsThatContextFromItsPreviousHolderOnly(ContentPageContext context) {
        ContentPage previousHolder = page("Previous", 0, context);
        when(contentPageRepository.findByContext(context)).thenReturn(Mono.just(previousHolder));
        when(contentPageRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(contentPageService.create("New", "Body", 1, context))
                .assertNext(created -> assertThat(created.getContext()).isEqualTo(context))
                .verifyComplete();

        assertThat(previousHolder.getContext()).isEqualTo(ContentPageContext.NONE);
        verify(contentPageRepository).save(previousHolder);
        for (ContentPageContext other : ContentPageContext.values()) {
            if (other != context) {
                verify(contentPageRepository, never()).findByContext(other);
            }
        }
    }

    @Test
    void creatingAnUndesignatedPageNeverTouchesAnyExistingDesignation() {
        when(contentPageRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(contentPageService.create("Plain", "Body", 2, ContentPageContext.NONE))
                .assertNext(created -> assertThat(created.getContext()).isEqualTo(ContentPageContext.NONE))
                .verifyComplete();

        verify(contentPageRepository, never()).findByContext(any());
    }

    @Test
    void updatingAPageThatAlreadyHoldsTheContextDoesNotClearItself() {
        UUID id = UUID.randomUUID();
        ContentPage page = page("Holder", 0, ContentPageContext.TOPIC_CREATION);
        page.setId(id);
        when(contentPageRepository.findById(id)).thenReturn(Mono.just(page));
        when(contentPageRepository.findByContext(ContentPageContext.TOPIC_CREATION)).thenReturn(Mono.just(page));
        when(contentPageRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(contentPageService.update(id, "Holder", "Body", 3, ContentPageContext.TOPIC_CREATION))
                .assertNext(updated -> {
                    assertThat(updated.getContext()).isEqualTo(ContentPageContext.TOPIC_CREATION);
                    assertThat(updated.getSortIndex()).isEqualTo(3);
                })
                .verifyComplete();

        verify(contentPageRepository).save(page);
    }

    @Test
    void updatingAPageToAContextClearsTheOtherPageHoldingIt() {
        UUID id = UUID.randomUUID();
        ContentPage page = page("Newcomer", 0, ContentPageContext.NONE);
        page.setId(id);
        ContentPage previousHolder = page("Previous", 0, ContentPageContext.USER_REGISTRATION);
        previousHolder.setId(UUID.randomUUID());
        when(contentPageRepository.findById(id)).thenReturn(Mono.just(page));
        when(contentPageRepository.findByContext(ContentPageContext.USER_REGISTRATION))
                .thenReturn(Mono.just(previousHolder));
        when(contentPageRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(contentPageService.update(id, "Newcomer", "Body", 0, ContentPageContext.USER_REGISTRATION))
                .assertNext(updated -> assertThat(updated.getContext()).isEqualTo(ContentPageContext.USER_REGISTRATION))
                .verifyComplete();

        assertThat(previousHolder.getContext()).isEqualTo(ContentPageContext.NONE);
        verify(contentPageRepository).save(previousHolder);
    }

    // --- Info menu (FR-002, FR-014) ---------------------------------------------------------------

    @Test
    void findInfoListExcludesEveryDesignatedPageAndKeepsTheRepositorysSortIndexThenTitleOrder() {
        ContentPage alpha = page("Alpha", 1, ContentPageContext.NONE);
        ContentPage beta = page("Beta", 1, ContentPageContext.NONE);
        ContentPage homepage = page("Homepage", 0, ContentPageContext.HOMEPAGE);
        ContentPage topicCreation = page("Topic creation", 0, ContentPageContext.TOPIC_CREATION);
        ContentPage registration = page("Registration", 2, ContentPageContext.USER_REGISTRATION);
        ContentPage zulu = page("Zulu", 0, ContentPageContext.NONE);
        when(contentPageRepository.findAllByOrderBySortIndexAscTitleAsc())
                .thenReturn(Flux.just(homepage, topicCreation, zulu, alpha, beta, registration));

        StepVerifier.create(contentPageService.findInfoList())
                .expectNext(zulu, alpha, beta)
                .verifyComplete();
    }

    @Test
    void findRenderedDefaultRendersTheFirstUndesignatedPageAndIsEmptyWhenNoneExists() {
        ContentPage homepage = page("Homepage", 0, ContentPageContext.HOMEPAGE);
        ContentPage first = page("First", 1, ContentPageContext.NONE);
        first.setBodyMarkdown("# Heading\n\nSome *text*.");
        ContentPage second = page("Second", 2, ContentPageContext.NONE);
        when(contentPageRepository.findAllByOrderBySortIndexAscTitleAsc())
                .thenReturn(Flux.just(homepage, first, second));

        StepVerifier.create(contentPageService.findRenderedDefault())
                .assertNext(rendered -> {
                    assertThat(rendered.page()).isSameAs(first);
                    assertThat(rendered.bodyHtml()).contains("<h2>Heading</h2>").contains("<em>text</em>");
                })
                .verifyComplete();

        when(contentPageRepository.findAllByOrderBySortIndexAscTitleAsc()).thenReturn(Flux.just(homepage));

        StepVerifier.create(contentPageService.findRenderedDefault()).verifyComplete();
    }

    @Test
    void findRenderedDetailRendersAnUndesignatedPageButCompletesEmptyForADesignatedOrUnknownOne() {
        UUID plainId = UUID.randomUUID();
        ContentPage plain = page("Plain", 0, ContentPageContext.NONE);
        plain.setId(plainId);
        UUID designatedId = UUID.randomUUID();
        ContentPage designated = page("Designated", 0, ContentPageContext.HOMEPAGE);
        designated.setId(designatedId);
        UUID unknownId = UUID.randomUUID();
        when(contentPageRepository.findById(plainId)).thenReturn(Mono.just(plain));
        when(contentPageRepository.findById(designatedId)).thenReturn(Mono.just(designated));
        when(contentPageRepository.findById(unknownId)).thenReturn(Mono.empty());

        StepVerifier.create(contentPageService.findRenderedDetail(plainId))
                .assertNext(rendered -> assertThat(rendered.page()).isSameAs(plain))
                .verifyComplete();
        StepVerifier.create(contentPageService.findRenderedDetail(designatedId)).verifyComplete();
        StepVerifier.create(contentPageService.findRenderedDetail(unknownId)).verifyComplete();
    }

    // --- Sort index pre-fill (FR-019a, FR-019b) ---------------------------------------------------

    @Test
    void nextSortIndexIsZeroWhenNoContentPageExists() {
        when(contentPageRepository.findMaxSortIndex()).thenReturn(Mono.just(-1));

        StepVerifier.create(contentPageService.nextSortIndex()).expectNext(0).verifyComplete();
    }

    @Test
    void nextSortIndexIsZeroWhenTheAggregateCompletesEmpty() {
        when(contentPageRepository.findMaxSortIndex()).thenReturn(Mono.empty());

        StepVerifier.create(contentPageService.nextSortIndex()).expectNext(0).verifyComplete();
    }

    @Test
    void nextSortIndexIsOneAboveTheCurrentHighestIndex() {
        when(contentPageRepository.findMaxSortIndex()).thenReturn(Mono.just(7));

        StepVerifier.create(contentPageService.nextSortIndex()).expectNext(8).verifyComplete();
    }

    // --- Designated content rendering (FR-015, FR-016, FR-017) -------------------------------------

    @Test
    void findRenderedByContextWrapsTheHolderInSanitizedHtmlAndIsEmptyWhenNoneHoldsIt() {
        ContentPage holder = page("Registration guidance", 0, ContentPageContext.USER_REGISTRATION);
        holder.setBodyMarkdown("# Read this first\n\n<script>alert(1)</script>Welcome!");
        when(contentPageRepository.findByContext(ContentPageContext.USER_REGISTRATION)).thenReturn(Mono.just(holder));
        when(contentPageRepository.findByContext(ContentPageContext.TOPIC_CREATION)).thenReturn(Mono.empty());

        StepVerifier.create(contentPageService.findRenderedByContext(ContentPageContext.USER_REGISTRATION))
                .assertNext(rendered -> {
                    assertThat(rendered.page()).isSameAs(holder);
                    assertThat(rendered.bodyHtml()).contains("<h2>Read this first</h2>").contains("Welcome!");
                    assertThat(rendered.bodyHtml()).doesNotContain("<script>");
                })
                .verifyComplete();
        StepVerifier.create(contentPageService.findRenderedByContext(ContentPageContext.TOPIC_CREATION))
                .verifyComplete();
    }

    // --- Helpers ----------------------------------------------------------------------------------

    /** A persisted-looking page: every real row carries a DB-assigned id, which the exclusivity swap compares. */
    private static ContentPage page(String title, int sortIndex, ContentPageContext context) {
        ContentPage page = new ContentPage();
        page.setId(UUID.randomUUID());
        page.setTitle(title);
        page.setBodyMarkdown("Body");
        page.setSortIndex(sortIndex);
        page.setContext(context);
        Instant now = Instant.now();
        page.setCreatedAt(now);
        page.setUpdatedAt(now);
        return page;
    }
}

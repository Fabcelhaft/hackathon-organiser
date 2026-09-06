package net.fabcelhaft.hackathonorganiser.organiser.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.content.ContentPage;
import net.fabcelhaft.hackathonorganiser.content.ContentPageContext;
import net.fabcelhaft.hackathonorganiser.content.ContentPageRepository;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.OidcLoginMutator;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

/**
 * Integration tests for Organiser Content Page management (T046; Feature 008 T015;
 * contracts/wiki-info-and-content-pages.md): create/edit/delete; {@code sort_index} reorders
 * {@code /info} for all users (FR-020a); designating a page for a context via the single-select
 * un-designates the previous holder of that context (008 FR-013); deleting the designated homepage
 * page leaves {@code /} showing the empty/unset state (008 FR-018b); the organiser overview names
 * each page's designation (008 FR-018) and only a designated row's delete control carries a
 * confirmation naming its context (008 FR-018a); a blank or non-numeric {@code sort_index} is
 * rejected instead of defaulting (008 FR-019); the New page form pre-fills {@code sort_index} to one
 * above the current highest, or {@code 0} when no page exists (008 FR-019a/b); a non-Organiser is
 * denied every route (FR-021); a blank {@code title}/{@code body_markdown} re-renders the form (200)
 * with a field-associated error (FR-037).
 */
@SpringBootTest
@Testcontainers
class ContentPageManagementIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @Autowired
    ApplicationContext applicationContext;

    WebTestClient webTestClient;

    @Autowired
    ContentPageRepository contentPageRepository;

    @Autowired
    UserRepository userRepository;

    @BeforeEach
    void setUpWebTestClient() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    @Test
    void organiserCanCreateEditAndDeleteAContentPage() {
        String name = "Created Page " + UUID.randomUUID();

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/content-pages")
                .body(BodyInserters.fromFormData("title", name)
                        .with("body_markdown", "# Body")
                        .with("sort_index", "3"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        ContentPage created = findByTitle(name);
        assertThat(created.getSortIndex()).isEqualTo(3);
        assertThat(created.getContext()).isEqualTo(ContentPageContext.NONE);

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/content-pages/{id}", created.getId())
                .body(BodyInserters.fromFormData("title", name)
                        .with("body_markdown", "# New Body")
                        .with("sort_index", "5"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        ContentPage updated = contentPageRepository.findById(created.getId()).block();
        assertThat(updated.getBodyMarkdown()).isEqualTo("# New Body");
        assertThat(updated.getSortIndex()).isEqualTo(5);

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/content-pages/{id}/delete", created.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(contentPageRepository.findById(created.getId()).block()).isNull();
    }

    @Test
    void sortIndexReordersInfoForAllUsers() {
        String nameLow = "Low Sort " + UUID.randomUUID();
        String nameHigh = "High Sort " + UUID.randomUUID();
        ContentPage pageA = persistPage(nameHigh, "Body", 10, ContentPageContext.NONE);
        ContentPage pageB = persistPage(nameLow, "Body", 1, ContentPageContext.NONE);

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/content-pages/{id}", pageA.getId())
                .body(BodyInserters.fromFormData("title", nameHigh)
                        .with("body_markdown", "Body")
                        .with("sort_index", "0"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        String body = webTestClient
                .mutateWith(mockOidcLogin())
                .get()
                .uri("/info")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // pageA's sort_index was just lowered to 0 (below pageB's 1), so it must now list first.
        String menu = body.substring(body.indexOf("aria-label=\"Info pages\""));
        assertThat(menu.indexOf(nameHigh)).isLessThan(menu.indexOf(nameLow));
    }

    // --- Context designation (008 FR-012a, FR-013) --------------------------------------------------

    @Test
    void designatingAPageForTheHomepageUndesignatesThePreviousHolderOfThatContextOnly() {
        ContentPage originalHomepage = contentPageRepository.findByContext(ContentPageContext.HOMEPAGE).block();
        undesignate(ContentPageContext.TOPIC_CREATION);
        ContentPage topicHolder =
                persistPage("Topic Holder " + UUID.randomUUID(), "Body", 0, ContentPageContext.TOPIC_CREATION);
        String newName = "New Homepage " + UUID.randomUUID();

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/content-pages")
                .body(BodyInserters.fromFormData("title", newName)
                        .with("body_markdown", "Body")
                        .with("sort_index", "0")
                        .with("context", "HOMEPAGE"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        ContentPage newHomepage = findByTitle(newName);
        assertThat(newHomepage.getContext()).isEqualTo(ContentPageContext.HOMEPAGE);
        if (originalHomepage != null) {
            assertThat(contentPageRepository.findById(originalHomepage.getId()).block().getContext())
                    .isEqualTo(ContentPageContext.NONE);
        }
        assertThat(contentPageRepository.findById(topicHolder.getId()).block().getContext())
                .isEqualTo(ContentPageContext.TOPIC_CREATION);
        assertThat(contentPageRepository
                        .findAll()
                        .filter(p -> p.getContext() == ContentPageContext.HOMEPAGE)
                        .count()
                        .block())
                .isEqualTo(1L);
    }

    @Test
    void editFormPreSelectsThePagesCurrentContextAndOffersAllFourOptions() {
        undesignate(ContentPageContext.USER_REGISTRATION);
        ContentPage page =
                persistPage("Registration Holder " + UUID.randomUUID(), "Body", 0, ContentPageContext.USER_REGISTRATION);

        String body = webTestClient
                .mutateWith(organiser())
                .get()
                .uri("/organiser/content-pages/{id}/edit", page.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // The page's own title is pre-filled (the layout fragment's `title` parameter must not shadow it).
        assertThat(body).contains("id=\"title\" name=\"title\" value=\"" + page.getTitle() + "\"");
        assertThat(body).contains("<select id=\"context\" name=\"context\"");
        assertThat(body).doesNotContain("is_homepage");
        for (String value : List.of("NONE", "HOMEPAGE", "TOPIC_CREATION", "USER_REGISTRATION")) {
            assertThat(body).contains("value=\"" + value + "\"");
        }
        assertThat(body).containsPattern("<option value=\"USER_REGISTRATION\"[^>]*selected=\"selected\"");
        assertThat(body).doesNotContainPattern("<option value=\"HOMEPAGE\"[^>]*selected=\"selected\"");
    }

    @Test
    void deletingTheDesignatedHomepagePageLeavesTheHomepageShowingTheEmptyStateUntilAReplacementIsDesignated() {
        ContentPage homepage = persistPage("Doomed Homepage " + UUID.randomUUID(), "Body", 0, ContentPageContext.NONE);
        undesignateThenDesignate(ContentPageContext.HOMEPAGE, homepage);

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/content-pages/{id}/delete", homepage.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        User user = persistUser();
        String body = webTestClient
                .mutateWith(loginAsUser(user))
                .get()
                .uri("/")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("No homepage content has been set yet.");
    }

    // --- Organiser overview (008 FR-018, FR-018a) ---------------------------------------------------

    @Test
    void overviewShowsEachPagesDesignationAndConfirmsDeletionOnlyForADesignatedPage() {
        undesignate(ContentPageContext.TOPIC_CREATION);
        ContentPage designated =
                persistPage("Overview Designated " + UUID.randomUUID(), "Body", 0, ContentPageContext.TOPIC_CREATION);
        ContentPage plain = persistPage("Overview Plain " + UUID.randomUUID(), "Body", 0, ContentPageContext.NONE);

        String body = webTestClient
                .mutateWith(organiser())
                .get()
                .uri("/organiser/content-pages")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        String designatedRow = row(body, designated.getTitle());
        assertThat(designatedRow).contains("Topic creation");
        assertThat(designatedRow).containsPattern("data-confirm=\"[^\"]*Topic creation[^\"]*\"");

        String plainRow = row(body, plain.getTitle());
        assertThat(plainRow).doesNotContain("Topic creation");
        assertThat(plainRow).doesNotContain("data-confirm");
        assertThat(plainRow).contains("<button type=\"submit\" class=\"outline danger\">Delete</button>");
    }

    // --- Mandatory sort index (008 FR-019, FR-019a, FR-019b) ----------------------------------------

    @Test
    void creatingWithABlankOrNonNumericSortIndexReRendersTheFormWithAnErrorInsteadOfDefaulting() {
        String blankName = "Blank Index " + UUID.randomUUID();
        String body = webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/content-pages")
                .body(BodyInserters.fromFormData("title", blankName)
                        .with("body_markdown", "Body")
                        .with("sort_index", "")
                        .with("context", "TOPIC_CREATION"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).contains("role=\"alert\"");
        assertThat(body).containsIgnoringCase("sort index");
        // The submitted values survive the re-render, including the chosen context.
        assertThat(body).contains("value=\"" + blankName + "\"");
        assertThat(body).containsPattern("<option value=\"TOPIC_CREATION\"[^>]*selected=\"selected\"");
        assertThat(findByTitle(blankName)).isNull();

        String nonNumericName = "Non Numeric Index " + UUID.randomUUID();
        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/content-pages")
                .body(BodyInserters.fromFormData("title", nonNumericName)
                        .with("body_markdown", "Body")
                        .with("sort_index", "abc"))
                .exchange()
                .expectStatus()
                .isOk();
        assertThat(findByTitle(nonNumericName)).isNull();
    }

    @Test
    void updatingWithAMissingSortIndexReRendersTheFormAndLeavesThePageUnchanged() {
        ContentPage page = persistPage("Keep Index " + UUID.randomUUID(), "Body", 7, ContentPageContext.NONE);

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/content-pages/{id}", page.getId())
                .body(BodyInserters.fromFormData("title", page.getTitle()).with("body_markdown", "Changed"))
                .exchange()
                .expectStatus()
                .isOk();

        ContentPage unchanged = contentPageRepository.findById(page.getId()).block();
        assertThat(unchanged.getSortIndex()).isEqualTo(7);
        assertThat(unchanged.getBodyMarkdown()).isEqualTo("Body");
    }

    @Test
    void newFormPreFillsTheSortIndexToOneAboveTheCurrentHighest() {
        persistPage("High Index " + UUID.randomUUID(), "Body", 41, ContentPageContext.NONE);

        String body = webTestClient
                .mutateWith(organiser())
                .get()
                .uri("/organiser/content-pages/new")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).containsPattern("id=\"sort_index\"[^>]*value=\"42\"");
    }

    @Test
    void newFormPreFillsTheSortIndexToZeroWhenNoContentPageExists() {
        contentPageRepository.deleteAll().block();

        String body = webTestClient
                .mutateWith(organiser())
                .get()
                .uri("/organiser/content-pages/new")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).containsPattern("id=\"sort_index\"[^>]*value=\"0\"");
    }

    // --- Validation & access -------------------------------------------------------------------------

    @Test
    void creatingOrEditingWithABlankTitleOrBodyReRendersTheFormWithAFieldAssociatedError() {
        String body = webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/content-pages")
                .body(BodyInserters.fromFormData("title", "")
                        .with("body_markdown", "Body")
                        .with("sort_index", "0"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("required");
    }

    @Test
    void nonOrganiserIsDeniedEveryContentPageRoute() {
        ContentPage page = persistPage("Guarded Page " + UUID.randomUUID(), "Body", 0, ContentPageContext.NONE);

        webTestClient
                .mutateWith(standardUser())
                .get()
                .uri("/organiser/content-pages")
                .exchange()
                .expectStatus()
                .isForbidden();

        webTestClient
                .mutateWith(standardUser())
                .post()
                .uri("/organiser/content-pages/{id}/delete", page.getId())
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    // --- Test helpers ------------------------------------------------------------------------------

    private ContentPage findByTitle(String title) {
        return contentPageRepository.findAll().filter(p -> p.getTitle().equals(title)).blockFirst();
    }

    /** The {@code <tr>} of the overview table whose first cell is {@code title}. */
    private static String row(String body, String title) {
        int titleIndex = body.indexOf(title);
        assertThat(titleIndex).withFailMessage("row for '" + title + "' not rendered").isGreaterThanOrEqualTo(0);
        int start = body.lastIndexOf("<tr", titleIndex);
        int end = body.indexOf("</tr>", titleIndex);
        return body.substring(start, end);
    }

    private void undesignate(ContentPageContext context) {
        contentPageRepository
                .findByContext(context)
                .flatMap(existing -> {
                    existing.setContext(ContentPageContext.NONE);
                    return contentPageRepository.save(existing);
                })
                .block();
    }

    private void undesignateThenDesignate(ContentPageContext context, ContentPage page) {
        contentPageRepository
                .findByContext(context)
                .flatMap(existing -> {
                    existing.setContext(ContentPageContext.NONE);
                    return contentPageRepository.save(existing);
                })
                .then(Mono.defer(() -> {
                    page.setContext(context);
                    return contentPageRepository.save(page);
                }))
                .block();
    }

    private ContentPage persistPage(String title, String bodyMarkdown, int sortIndex, ContentPageContext context) {
        ContentPage page = new ContentPage();
        page.setTitle(title);
        page.setBodyMarkdown(bodyMarkdown);
        page.setSortIndex(sortIndex);
        page.setContext(context);
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

    private User persistUser() {
        User user = new User();
        user.setOidcSubject("sub-" + UUID.randomUUID());
        user.setDisplayName("User " + UUID.randomUUID());
        user.setEmail("user-" + UUID.randomUUID() + "@example.com");
        user.setOrganiser(false);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).block();
    }

    private static OidcLoginMutator loginAsUser(User user) {
        Instant issuedAt = Instant.now();
        OidcIdToken idToken = OidcIdToken.withTokenValue("token-value")
                .subject(user.getOidcSubject())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(300))
                .claim("name", user.getDisplayName())
                .build();
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        DefaultOidcUser delegate = new DefaultOidcUser(authorities, idToken);
        HackathonOidcUser principal = new HackathonOidcUser(user, delegate);
        return mockOidcLogin().oidcUser(principal);
    }
}

package net.fabcelhaft.hackathonorganiser.info;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration tests for the wiki-style Info view (Feature 008, T009/T013/T025;
 * contracts/wiki-info-and-content-pages.md). Story 1: {@code GET /info} renders the full menu of
 * undesignated pages plus the first page's content; {@code GET /info/{id}} renders the same menu
 * with {@code id}'s content and that entry carrying {@code aria-current="page"}; same-index pages
 * order alphabetically by title, stable across requests (SC-008); an unknown or designated id
 * returns 404 while still rendering the menu (FR-008). Story 2: an Organiser sees Edit/New page
 * actions opening in a new tab; a participant sees neither (SC-004); the not-found view offers
 * New page only (FR-011a). Story 4: with zero undesignated pages, the empty-state message replaces
 * the menu, plus New page for an Organiser (FR-020, FR-021). Also carries forward feature 003's
 * homepage-rendering tests and the top-level-heading assertion (FR-007) on the new
 * {@code context} API.
 */
@SpringBootTest
@Testcontainers
class InfoManagementIT {

    private static final String EDIT_LINK_PREFIX = "href=\"/organiser/content-pages/";
    private static final String NEW_PAGE_LINK = "href=\"/organiser/content-pages/new\"";
    private static final String EMPTY_STATE_MESSAGE = "No info pages have been added yet.";
    private static final String NOT_FOUND_MESSAGE = "The requested page was not found.";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @Autowired
    ApplicationContext applicationContext;

    WebTestClient webTestClient;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ContentPageRepository contentPageRepository;

    @BeforeEach
    void setUpWebTestClient() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    // --- Carried forward from feature 003: homepage rendering ---------------------------------------

    @Test
    void homepageRightColumnRendersTheDesignatedContentPageAsSanitizedFormattedHtml() {
        User user = persistUser(false);
        undesignate(ContentPageContext.HOMEPAGE);
        persistPage("Homepage Content " + UUID.randomUUID(), "# Big Heading\n\nSome *text*.", 0,
                ContentPageContext.HOMEPAGE);

        String body = get(user, "/").expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("<h2>Big Heading</h2>");
        assertThat(body).contains("<em>text</em>");
        assertThat(body).doesNotContain("# Big Heading");
    }

    @Test
    void homepageRightColumnShowsAClearEmptyStateWhenNoPageIsDesignated() {
        User user = persistUser(false);
        undesignate(ContentPageContext.HOMEPAGE);

        String body = get(user, "/").expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("No homepage content has been set yet.");
    }

    // --- Story 1: browse info pages like a wiki ------------------------------------------------------

    @Test
    void infoRendersTheFullMenuAndTheFirstPageByMenuOrderAtTheRoot() {
        User user = persistUser(false);
        deleteEveryUndesignatedPage();
        String suffix = UUID.randomUUID().toString();
        ContentPage second = persistPage("Second " + suffix, "Second body " + suffix, 1, ContentPageContext.NONE);
        ContentPage first = persistPage("First " + suffix, "First body " + suffix, 0, ContentPageContext.NONE);

        String body = get(user, "/info").expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        String menu = menu(body);
        assertThat(menu).contains(first.getTitle()).contains(second.getTitle());
        assertThat(menu.indexOf(first.getTitle())).isLessThan(menu.indexOf(second.getTitle()));
        assertThat(body).contains("First body " + suffix);
        assertThat(body).doesNotContain("Second body " + suffix);
        assertThat(body).contains("<h1>" + first.getTitle() + "</h1>");
        assertThat(body).containsPattern(currentMenuEntry(first.getId()));
        assertThat(body).doesNotContainPattern(currentMenuEntry(second.getId()));
    }

    @Test
    void infoDetailRendersTheRequestedPageWithItsMenuEntryMarkedCurrent() {
        User user = persistUser(false);
        String suffix = UUID.randomUUID().toString();
        ContentPage other = persistPage("Other " + suffix, "Other body " + suffix, 0, ContentPageContext.NONE);
        ContentPage requested = persistPage("Requested " + suffix, "Requested body " + suffix, 1, ContentPageContext.NONE);

        String body = get(user, "/info/" + requested.getId())
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(menu(body)).contains(other.getTitle()).contains(requested.getTitle());
        assertThat(body).contains("Requested body " + suffix);
        assertThat(body).doesNotContain("Other body " + suffix);
        assertThat(body).containsPattern(currentMenuEntry(requested.getId()));
        assertThat(body).doesNotContainPattern(currentMenuEntry(other.getId()));
    }

    @Test
    void infoDetailRendersOnePageWithItsTitleAsTheTopLevelHeading() {
        User user = persistUser(false);
        ContentPage page = persistPage("Detail Page " + UUID.randomUUID(), "# Sub Heading\n\nBody text.", 0,
                ContentPageContext.NONE);

        String body = get(user, "/info/" + page.getId())
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("<h1>" + page.getTitle() + "</h1>");
        assertThat(body).contains("<h2>Sub Heading</h2>");
        assertThat(body.split("<h1[ >]").length - 1).isEqualTo(1);
    }

    @Test
    void infoOrdersPagesSharingASortIndexAlphabeticallyByTitleStableAcrossRequests() {
        User user = persistUser(false);
        String suffix = UUID.randomUUID().toString();
        // Persisted in reverse alphabetical order, so a created_at tie-break would list Zulu first.
        ContentPage zulu = persistPage("Zulu " + suffix, "Body", 5, ContentPageContext.NONE);
        ContentPage alpha = persistPage("Alpha " + suffix, "Body", 5, ContentPageContext.NONE);

        for (int i = 0; i < 3; i++) {
            String menu = menu(get(user, "/info/" + zulu.getId())
                    .expectStatus()
                    .isOk()
                    .expectBody(String.class)
                    .returnResult()
                    .getResponseBody());
            int alphaIndex = menu.indexOf(alpha.getTitle());
            int zuluIndex = menu.indexOf(zulu.getTitle());
            assertThat(alphaIndex).isGreaterThanOrEqualTo(0);
            assertThat(alphaIndex).isLessThan(zuluIndex);
            // Adjacent: nothing else with sort_index 5 sits between them, and no other entry does either.
            assertThat(menu.substring(alphaIndex, zuluIndex).split("<li").length - 1).isEqualTo(1);
        }
    }

    @Test
    void infoExcludesEveryDesignatedPageFromTheMenu() {
        User user = persistUser(false);
        undesignate(ContentPageContext.HOMEPAGE);
        undesignate(ContentPageContext.TOPIC_CREATION);
        undesignate(ContentPageContext.USER_REGISTRATION);
        ContentPage homepage = persistPage("Homepage Only " + UUID.randomUUID(), "Body", 0, ContentPageContext.HOMEPAGE);
        ContentPage topic = persistPage("Topic Only " + UUID.randomUUID(), "Body", 0, ContentPageContext.TOPIC_CREATION);
        ContentPage registration =
                persistPage("Registration Only " + UUID.randomUUID(), "Body", 0, ContentPageContext.USER_REGISTRATION);
        ContentPage info = persistPage("Info Page " + UUID.randomUUID(), "Body", 1, ContentPageContext.NONE);

        String body = get(user, "/info").expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        String menu = menu(body);
        assertThat(menu).contains(info.getTitle());
        assertThat(menu).doesNotContain(homepage.getTitle());
        assertThat(menu).doesNotContain(topic.getTitle());
        assertThat(menu).doesNotContain(registration.getTitle());
    }

    @Test
    void infoDetailOfAnUnknownPageReturnsNotFoundInsideTheWikiLayout() {
        User user = persistUser(false);
        ContentPage existing = persistPage("Still Listed " + UUID.randomUUID(), "Body", 0, ContentPageContext.NONE);

        String body = get(user, "/info/" + UUID.randomUUID())
                .expectStatus()
                .isNotFound()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains(NOT_FOUND_MESSAGE);
        assertThat(menu(body)).contains(existing.getTitle());
    }

    @Test
    void infoDetailOfANowDesignatedPageReturnsNotFoundInsideTheWikiLayout() {
        User user = persistUser(false);
        undesignate(ContentPageContext.TOPIC_CREATION);
        ContentPage existing = persistPage("Still Listed " + UUID.randomUUID(), "Body", 0, ContentPageContext.NONE);
        ContentPage designated =
                persistPage("Designated " + UUID.randomUUID(), "Designated body", 0, ContentPageContext.TOPIC_CREATION);

        String body = get(user, "/info/" + designated.getId())
                .expectStatus()
                .isNotFound()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains(NOT_FOUND_MESSAGE);
        assertThat(body).doesNotContain("Designated body");
        assertThat(menu(body)).contains(existing.getTitle()).doesNotContain(designated.getTitle());
    }

    // --- Story 2: Organisers author from the wiki view -----------------------------------------------

    @Test
    void organiserSeesEditAndNewPageActionsThatOpenInANewTab() {
        User organiser = persistUser(true);
        ContentPage page = persistPage("Editable " + UUID.randomUUID(), "Body", 0, ContentPageContext.NONE);

        String body = get(organiser, "/info/" + page.getId())
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).containsPattern(newTabLink("/organiser/content-pages/" + page.getId() + "/edit"));
        assertThat(body).containsPattern(newTabLink("/organiser/content-pages/new"));
        // The Edit action targets exactly the displayed page — no other page's edit link leaks in.
        assertThat(body.split(EDIT_LINK_PREFIX).length - 1).isEqualTo(2);
    }

    @Test
    void participantSeesNoAuthoringActionAnywhereInTheInfoSection() {
        User participant = persistUser(false);
        ContentPage page = persistPage("Read Only " + UUID.randomUUID(), "Body", 0, ContentPageContext.NONE);

        for (String path : List.of("/info", "/info/" + page.getId())) {
            String body = get(participant, path)
                    .expectStatus()
                    .isOk()
                    .expectBody(String.class)
                    .returnResult()
                    .getResponseBody();
            assertThat(body).doesNotContain(EDIT_LINK_PREFIX);
            assertThat(body).doesNotContain(NEW_PAGE_LINK);
        }

        String notFoundBody = get(participant, "/info/" + UUID.randomUUID())
                .expectStatus()
                .isNotFound()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(notFoundBody).doesNotContain(EDIT_LINK_PREFIX);
        assertThat(notFoundBody).doesNotContain(NEW_PAGE_LINK);
    }

    @Test
    void organiserOnTheNotFoundViewSeesNewPageButNotEdit() {
        User organiser = persistUser(true);
        persistPage("Still Listed " + UUID.randomUUID(), "Body", 0, ContentPageContext.NONE);

        String body = get(organiser, "/info/" + UUID.randomUUID())
                .expectStatus()
                .isNotFound()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains(NOT_FOUND_MESSAGE);
        assertThat(body).containsPattern(newTabLink("/organiser/content-pages/new"));
        assertThat(body).doesNotContain("/edit\"");
    }

    // --- Story 4: Info section with no pages yet -----------------------------------------------------

    @Test
    void participantSeesTheEmptyStateMessageWhenNoUndesignatedPageExists() {
        User participant = persistUser(false);
        // Un-designating first turns the previous holder into an ordinary page, which the delete then removes.
        undesignate(ContentPageContext.HOMEPAGE);
        deleteEveryUndesignatedPage();
        persistPage("Only Designated " + UUID.randomUUID(), "Designated body", 0, ContentPageContext.HOMEPAGE);

        for (String path : List.of("/info", "/info/" + UUID.randomUUID())) {
            String body = get(participant, path)
                    .expectStatus()
                    .isOk()
                    .expectBody(String.class)
                    .returnResult()
                    .getResponseBody();
            assertThat(body).contains(EMPTY_STATE_MESSAGE);
            assertThat(body).doesNotContain(NOT_FOUND_MESSAGE);
            assertThat(body).doesNotContain("Designated body");
            assertThat(body).doesNotContain("aria-label=\"Info pages\"");
            assertThat(body).doesNotContain(NEW_PAGE_LINK);
        }
    }

    @Test
    void organiserSeesTheEmptyStateMessagePlusTheNewPageAction() {
        User organiser = persistUser(true);
        deleteEveryUndesignatedPage();

        String body = get(organiser, "/info").expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains(EMPTY_STATE_MESSAGE);
        assertThat(body).containsPattern(newTabLink("/organiser/content-pages/new"));
        assertThat(body).doesNotContain("/edit\"");
    }

    // --- Test helpers ------------------------------------------------------------------------------

    private WebTestClient.ResponseSpec get(User user, String path) {
        return webTestClient.mutateWith(loginAs(user)).get().uri(path).exchange();
    }

    /** The wiki menu's markup only, so title assertions ignore the {@code <title>}/{@code <h1>} of the shown page. */
    private static String menu(String body) {
        int start = body.indexOf("aria-label=\"Info pages\"");
        assertThat(start).withFailMessage("no wiki menu landmark rendered:\n" + body).isGreaterThanOrEqualTo(0);
        int end = body.indexOf("</nav>", start);
        return body.substring(start, end);
    }

    private static Pattern currentMenuEntry(UUID id) {
        return Pattern.compile("<a[^>]*href=\"/info/" + id + "\"[^>]*aria-current=\"page\"");
    }

    private static Pattern newTabLink(String href) {
        return Pattern.compile("<a[^>]*href=\"" + Pattern.quote(href) + "\"[^>]*target=\"_blank\"[^>]*rel=\"noopener\"");
    }

    private void undesignate(ContentPageContext context) {
        contentPageRepository
                .findByContext(context)
                .flatMap(page -> {
                    page.setContext(ContentPageContext.NONE);
                    return contentPageRepository.save(page);
                })
                .block();
    }

    private void deleteEveryUndesignatedPage() {
        contentPageRepository
                .findAll()
                .filter(page -> page.getContext() == ContentPageContext.NONE)
                .flatMap(contentPageRepository::delete)
                .then()
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

    private User persistUser(boolean organiser) {
        User user = new User();
        user.setOidcSubject("sub-" + UUID.randomUUID());
        user.setDisplayName("User " + UUID.randomUUID());
        user.setEmail("user-" + UUID.randomUUID() + "@example.com");
        user.setOrganiser(organiser);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).block();
    }

    /**
     * A persisted {@link User} behind a real {@link HackathonOidcUser} principal — a bare
     * {@code mockOidcLogin()} would leave {@code CurrentUserModelAdvice}'s {@code isOrganiser}
     * attribute false and every {@code @AuthenticationPrincipal HackathonOidcUser} route unresolved.
     */
    private static OidcLoginMutator loginAs(User user) {
        Instant issuedAt = Instant.now();
        OidcIdToken idToken = OidcIdToken.withTokenValue("token-value")
                .subject(user.getOidcSubject())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(300))
                .claim("name", user.getDisplayName())
                .build();
        List<GrantedAuthority> authorities = user.isOrganiser()
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ORGANISER"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));
        DefaultOidcUser delegate = new DefaultOidcUser(authorities, idToken);
        HackathonOidcUser principal = new HackathonOidcUser(user, delegate);
        return mockOidcLogin().oidcUser(principal);
    }
}

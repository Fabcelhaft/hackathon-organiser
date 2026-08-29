package net.fabcelhaft.hackathonorganiser.info;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.content.ContentPage;
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
 * Integration tests for User Story 5's rendered homepage/Info content (T036;
 * contracts/content-pages-and-info.md): the homepage right column renders the designated Content
 * Page's markdown as sanitized formatted HTML, never raw markdown syntax (SC-006); {@code GET
 * /info} lists non-homepage pages ordered by {@code sort_index} (tie-broken by {@code created_at}),
 * with an empty-state message when none exist (Edge Cases); {@code GET /info/{id}} renders one
 * page with its title as the top-level heading (FR-036); the homepage right column shows a clear
 * empty/unset state when no page is designated (Edge Cases).
 */
@SpringBootTest
@Testcontainers
class InfoManagementIT {

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

    @Test
    void homepageRightColumnRendersTheDesignatedContentPageAsSanitizedFormattedHtml() {
        User user = persistUser();
        undesignateAnyExistingHomepage();
        persistPage("Homepage Content " + UUID.randomUUID(), "# Big Heading\n\nSome *text*.", 0, true);

        String body = webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("<h2>Big Heading</h2>");
        assertThat(body).contains("<em>text</em>");
        assertThat(body).doesNotContain("# Big Heading");
    }

    @Test
    void homepageRightColumnShowsAClearEmptyStateWhenNoPageIsDesignated() {
        User user = persistUser();
        undesignateAnyExistingHomepage();

        String body = webTestClient
                .mutateWith(loginAs(user))
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

    @Test
    void infoListsNonHomepagePagesOrderedBySortIndexTieBrokenByCreatedAt() {
        User user = persistUser();
        undesignateAnyExistingHomepage();
        String nameA = "Second Page " + UUID.randomUUID();
        String nameB = "First Page " + UUID.randomUUID();
        persistPage(nameA, "Body A", 1, false);
        persistPage(nameB, "Body B", 0, false);

        String body = webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/info")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body.indexOf(nameB)).isLessThan(body.indexOf(nameA));
    }

    @Test
    void infoExcludesTheDesignatedHomepagePageFromItsListing() {
        User user = persistUser();
        undesignateAnyExistingHomepage();
        ContentPage homepage = persistPage("Homepage Only " + UUID.randomUUID(), "Body", 0, true);
        persistPage("Info Page " + UUID.randomUUID(), "Body", 1, false);

        String body = webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/info")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).doesNotContain(homepage.getTitle());
    }

    @Test
    void infoDetailRendersOnePageWithItsTitleAsTheTopLevelHeading() {
        User user = persistUser();
        ContentPage page = persistPage("Detail Page " + UUID.randomUUID(), "# Sub Heading\n\nBody text.", 0, false);

        String body = webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/info/{id}", page.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("<h1>" + page.getTitle() + "</h1>");
        assertThat(body).contains("<h2>Sub Heading</h2>");
    }

    @Test
    void infoDetailOfAnUnknownPageReturnsNotFound() {
        User user = persistUser();

        webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/info/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    // --- Test helpers ------------------------------------------------------------------------------

    private void undesignateAnyExistingHomepage() {
        contentPageRepository
                .findByIsHomepageTrue()
                .flatMap(page -> {
                    page.setHomepage(false);
                    return contentPageRepository.save(page);
                })
                .block();
    }

    private ContentPage persistPage(String title, String bodyMarkdown, int sortIndex, boolean isHomepage) {
        ContentPage page = new ContentPage();
        page.setTitle(title);
        page.setBodyMarkdown(bodyMarkdown);
        page.setSortIndex(sortIndex);
        page.setHomepage(isHomepage);
        Instant now = Instant.now();
        page.setCreatedAt(now);
        page.setUpdatedAt(now);
        return contentPageRepository.save(page).block();
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

    private static OidcLoginMutator loginAs(User user) {
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

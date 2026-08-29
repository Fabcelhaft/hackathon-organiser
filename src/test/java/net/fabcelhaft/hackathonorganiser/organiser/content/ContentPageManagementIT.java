package net.fabcelhaft.hackathonorganiser.organiser.content;

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
 * Integration tests for User Story 6's Organiser Content Page management (T046;
 * contracts/content-pages-and-info.md): create/edit/delete; {@code sort_index} reorders
 * {@code /info} for all users (FR-020a); designating a page as the homepage page un-designates
 * the previous one (FR-019); deleting the currently designated homepage page leaves {@code /}
 * showing the empty/unset state until a replacement is designated (Edge Cases); a non-Organiser is
 * denied every route (FR-021); a blank {@code title}/{@code body_markdown} re-renders the form
 * (200) with a field-associated error (FR-037).
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
        ContentPage pageA = persistPage(nameHigh, "Body", 10, false);
        ContentPage pageB = persistPage(nameLow, "Body", 1, false);

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
        assertThat(body.indexOf(nameHigh)).isLessThan(body.indexOf(nameLow));
    }

    @Test
    void designatingAPageAsHomepageUndesignatesThePreviousOne() {
        ContentPage originalHomepage = contentPageRepository.findByIsHomepageTrue().block();
        String newName = "New Homepage " + UUID.randomUUID();

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/content-pages")
                .body(BodyInserters.fromFormData("title", newName)
                        .with("body_markdown", "Body")
                        .with("sort_index", "0")
                        .with("is_homepage", "true"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        ContentPage newHomepage = findByTitle(newName);
        assertThat(newHomepage.isHomepage()).isTrue();
        if (originalHomepage != null) {
            assertThat(contentPageRepository.findById(originalHomepage.getId()).block().isHomepage())
                    .isFalse();
        }
        assertThat(contentPageRepository.findAll().filter(ContentPage::isHomepage).count().block())
                .isEqualTo(1L);
    }

    @Test
    void deletingTheDesignatedHomepagePageLeavesTheHomepageShowingTheEmptyStateUntilAReplacementIsDesignated() {
        ContentPage homepage = persistPage("Doomed Homepage " + UUID.randomUUID(), "Body", 0, false);
        undesignateExistingHomepageThenDesignate(homepage);

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

    @Test
    void creatingOrEditingWithABlankTitleOrBodyReRendersTheFormWithAFieldAssociatedError() {
        String body = webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/content-pages")
                .body(BodyInserters.fromFormData("title", "").with("body_markdown", "Body"))
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
        ContentPage page = persistPage("Guarded Page " + UUID.randomUUID(), "Body", 0, false);

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

    private void undesignateExistingHomepageThenDesignate(ContentPage page) {
        contentPageRepository
                .findByIsHomepageTrue()
                .flatMap(existing -> {
                    existing.setHomepage(false);
                    return contentPageRepository.save(existing);
                })
                .then(Mono.defer(() -> {
                    page.setHomepage(true);
                    return contentPageRepository.save(page);
                }))
                .block();
    }

    private ContentPage persistPage(String title, String bodyMarkdown, int sortIndex, boolean homepage) {
        ContentPage page = new ContentPage();
        page.setTitle(title);
        page.setBodyMarkdown(bodyMarkdown);
        page.setSortIndex(sortIndex);
        page.setHomepage(homepage);
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

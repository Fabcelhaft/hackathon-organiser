package net.fabcelhaft.hackathonorganiser.a11y;

import static org.assertj.core.api.Assertions.assertThat;

import com.deque.html.axecore.playwright.AxeBuilder;
import com.deque.html.axecore.results.AxeResults;
import com.deque.html.axecore.results.Rule;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabcelhaft.hackathonorganiser.content.ContentPage;
import net.fabcelhaft.hackathonorganiser.content.ContentPageContext;
import net.fabcelhaft.hackathonorganiser.content.ContentPageRepository;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

/**
 * Automated WCAG 2.1 AA scan for the wiki-style Info view (Feature 008, T028; quickstart.md
 * "Automated accessibility scan"), reusing {@link HomepageAccessibilityIT}'s exact pattern —
 * pre-authenticated session via a test-only login backdoor, system Chromium, Deque's
 * {@code AxeBuilder}. Scans every render branch of {@code info/index} for both a participant and an
 * Organiser session: the populated menu-plus-content layout (a landmark nav carrying
 * {@code aria-current}, one {@code <h1>} per page, the Organiser's Edit/New page actions), the
 * not-found view, and the empty state. Zero {@code critical}/{@code serious} violations are
 * asserted on each.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(InfoAccessibilityIT.TestLoginSupport.class)
class InfoAccessibilityIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @LocalServerPort
    int port;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ContentPageRepository contentPageRepository;

    static Playwright playwright;
    static Browser browser;
    BrowserContext context;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu"));
        Path systemChromium = resolveSystemChromium();
        if (systemChromium != null) {
            options.setExecutablePath(systemChromium);
        }
        browser = playwright.chromium().launch(options);
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void newContext() {
        context = browser.newContext();
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    // --- Populated wiki view (User Story 1, 2) --------------------------------------------------

    @Test
    void populatedWikiViewHasNoCriticalOrSeriousViolationsForAParticipant() {
        User participant = persistUser(false);
        persistContentPage("First Page", 0);
        ContentPage second = persistContentPage("Second Page", 1);
        loginAs(participant);

        Page defaultPage = context.newPage();
        defaultPage.navigate(baseUrl() + "/info");
        assertNoSeriousViolations(defaultPage, "/info (participant)");
        assertThat(defaultPage.locator("nav[aria-label='Info pages'] a[aria-current='page']").count()).isEqualTo(1);
        assertThat(defaultPage.locator("h1").count()).isEqualTo(1);

        Page detailPage = context.newPage();
        detailPage.navigate(baseUrl() + "/info/" + second.getId());
        assertNoSeriousViolations(detailPage, "/info/{id} (participant)");
        assertThat(detailPage.locator("a[aria-current='page']").getAttribute("href")).endsWith("/info/" + second.getId());
        assertThat(detailPage.locator("h1").textContent()).isEqualTo(second.getTitle());
    }

    @Test
    void populatedWikiViewWithAuthoringActionsHasNoCriticalOrSeriousViolationsForAnOrganiser() {
        User organiser = persistUser(true);
        ContentPage page = persistContentPage("Editable Page", 0);
        loginAs(organiser);

        Page wikiPage = context.newPage();
        wikiPage.navigate(baseUrl() + "/info/" + page.getId());
        assertNoSeriousViolations(wikiPage, "/info/{id} (organiser, with Edit/New page)");
        assertThat(wikiPage.locator("a[target='_blank'][href$='/edit']").count()).isEqualTo(1);
        assertThat(wikiPage.locator("a[target='_blank'][href$='/content-pages/new']").count()).isEqualTo(1);
    }

    // --- Not-found view (FR-008, FR-011a) -----------------------------------------------------------

    @Test
    void notFoundViewInsideTheWikiLayoutHasNoCriticalOrSeriousViolations() {
        persistContentPage("Still Listed", 0);

        User participant = persistUser(false);
        loginAs(participant);
        Page participantPage = context.newPage();
        participantPage.navigate(baseUrl() + "/info/" + UUID.randomUUID());
        assertNoSeriousViolations(participantPage, "/info/{unknown-id} (participant)");
        assertThat(participantPage.locator("nav[aria-label='Info pages'] li").count()).isGreaterThanOrEqualTo(1);
        assertThat(participantPage.locator("#info-not-found").count()).isEqualTo(1);

        context.close();
        context = browser.newContext();
        User organiser = persistUser(true);
        loginAs(organiser);
        Page organiserPage = context.newPage();
        organiserPage.navigate(baseUrl() + "/info/" + UUID.randomUUID());
        assertNoSeriousViolations(organiserPage, "/info/{unknown-id} (organiser)");
        assertThat(organiserPage.locator("a[target='_blank'][href$='/content-pages/new']").count()).isEqualTo(1);
        assertThat(organiserPage.locator("a[href$='/edit']").count()).isEqualTo(0);
    }

    // --- Empty state (User Story 4) -------------------------------------------------------------------

    @Test
    void emptyStateHasNoCriticalOrSeriousViolationsForParticipantAndOrganiser() {
        deleteEveryUndesignatedPage();

        User participant = persistUser(false);
        loginAs(participant);
        Page participantPage = context.newPage();
        participantPage.navigate(baseUrl() + "/info");
        assertNoSeriousViolations(participantPage, "/info (empty state, participant)");
        assertThat(participantPage.locator("#info-empty-state").count()).isEqualTo(1);
        assertThat(participantPage.locator("nav[aria-label='Info pages']").count()).isEqualTo(0);

        context.close();
        context = browser.newContext();
        User organiser = persistUser(true);
        loginAs(organiser);
        Page organiserPage = context.newPage();
        organiserPage.navigate(baseUrl() + "/info");
        assertNoSeriousViolations(organiserPage, "/info (empty state, organiser)");
        assertThat(organiserPage.locator("a[target='_blank'][href$='/content-pages/new']").count()).isEqualTo(1);
    }

    // --- Test support --------------------------------------------------------------------------

    private void assertNoSeriousViolations(Page page, String label) {
        AxeResults results = new AxeBuilder(page).analyze();
        List<Rule> seriousOrCritical = results.getViolations().stream()
                .filter(rule -> "serious".equals(rule.getImpact()) || "critical".equals(rule.getImpact()))
                .toList();
        assertThat(seriousOrCritical)
                .withFailMessage(() -> label + " has critical/serious WCAG 2.1 AA violations: "
                        + seriousOrCritical.stream()
                                .map(rule -> rule.getId() + " (" + rule.getImpact() + "): " + rule.getHelp())
                                .collect(Collectors.joining("; ")))
                .isEmpty();
    }

    private void loginAs(User user) {
        Page loginPage = context.newPage();
        loginPage.navigate(baseUrl() + "/__test-login?userId=" + user.getId());
        loginPage.close();
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private static Path resolveSystemChromium() {
        List<String> candidates = new ArrayList<>();
        String override = System.getenv("PLAYWRIGHT_CHROMIUM_EXECUTABLE");
        if (override != null && !override.isBlank()) {
            candidates.add(override);
        }
        candidates.add("/usr/bin/chromium");
        candidates.add("/usr/bin/chromium-browser");
        candidates.add("/usr/bin/google-chrome");
        return candidates.stream().map(Path::of).filter(Files::isExecutable).findFirst().orElse(null);
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

    private ContentPage persistContentPage(String title, int sortIndex) {
        ContentPage page = new ContentPage();
        page.setTitle(title + " " + UUID.randomUUID());
        page.setBodyMarkdown("# Heading\n\nSome *content* with a [link](https://example.com).\n\n## Section\n\n- one\n- two");
        page.setSortIndex(sortIndex);
        page.setContext(ContentPageContext.NONE);
        Instant now = Instant.now();
        page.setCreatedAt(now);
        page.setUpdatedAt(now);
        return contentPageRepository.save(page).block();
    }

    private void deleteEveryUndesignatedPage() {
        contentPageRepository
                .findAll()
                .filter(page -> page.getContext() == ContentPageContext.NONE)
                .flatMap(contentPageRepository::delete)
                .then()
                .block();
    }

    /**
     * Test-only pre-authentication backdoor (003 research.md §9), identical to {@link
     * HomepageAccessibilityIT.TestLoginSupport}: seeds a real {@link SecurityContext} into the
     * {@code WebSession} for {@code GET /__test-login?userId=...}. Registered only in this test's
     * {@code ApplicationContext}, never in production.
     */
    @TestConfiguration
    static class TestLoginSupport {

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        org.springframework.web.server.WebFilter testLoginFilter(UserRepository userRepository) {
            ServerSecurityContextRepository securityContextRepository =
                    new WebSessionServerSecurityContextRepository();
            return (exchange, chain) -> {
                if (!"/__test-login".equals(exchange.getRequest().getPath().value())) {
                    return chain.filter(exchange);
                }
                String userIdParam = exchange.getRequest().getQueryParams().getFirst("userId");
                return Mono.fromCallable(() -> UUID.fromString(userIdParam))
                        .flatMap(userRepository::findById)
                        .flatMap(user -> {
                            SecurityContext securityContext = new SecurityContextImpl(authenticationFor(user));
                            return securityContextRepository
                                    .save(exchange, securityContext)
                                    .then(Mono.defer(() -> {
                                        exchange.getResponse().setStatusCode(HttpStatus.OK);
                                        return exchange.getResponse().setComplete();
                                    }));
                        });
            };
        }

        private static Authentication authenticationFor(User user) {
            OidcIdToken idToken = OidcIdToken.withTokenValue("test-token")
                    .subject(user.getOidcSubject())
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .claim("name", user.getDisplayName())
                    .build();
            Set<GrantedAuthority> authorities = user.isOrganiser()
                    ? Set.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ORGANISER"))
                    : Set.of(new SimpleGrantedAuthority("ROLE_USER"));
            DefaultOidcUser delegate = new DefaultOidcUser(authorities, idToken);
            HackathonOidcUser principal = new HackathonOidcUser(user, delegate);
            return new UsernamePasswordAuthenticationToken(principal, null, authorities);
        }
    }
}

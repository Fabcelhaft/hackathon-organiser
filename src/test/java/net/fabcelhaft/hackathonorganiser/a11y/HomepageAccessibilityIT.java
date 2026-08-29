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
import net.fabcelhaft.hackathonorganiser.content.ContentPageRepository;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsRepository;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantRepository;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.topic.Topic;
import net.fabcelhaft.hackathonorganiser.topic.TopicApprovalStatus;
import net.fabcelhaft.hackathonorganiser.topic.TopicRepository;
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
 * Automated WCAG 2.1 AA scan for SC-009 (T062; research.md §9). {@code WebTestClient} never builds
 * a real accessibility tree, so this is a genuinely new test category: a real running server
 * ({@code @SpringBootTest(RANDOM_PORT)}, the same pattern {@link
 * net.fabcelhaft.hackathonorganiser.ActuatorHealthIT} already established) driven by headless
 * Chromium via Playwright, scanned with Deque's first-party {@code AxeBuilder} for Playwright.
 * Zero {@code critical}/{@code serious} violations are asserted on each in-scope screen: the
 * homepage, the topic propose/edit forms, the Organiser settings screen, Content Page and Content
 * Image management, and the Info section. Per FR-030's explicit scope note, 002's pre-existing
 * organiser screens (Users, Skills, Custom Fields, Groups) are intentionally excluded.
 *
 * <p>Authentication is a pre-authenticated session, not a UI login flow (no live IdP needed):
 * {@link TestLoginSupport} registers a test-only {@code WebFilter}, ordered ahead of {@code
 * SecurityConfig}'s chain, that seeds a real {@link SecurityContext} into the {@code WebSession}
 * for a given persisted {@link User} — the same {@link HackathonOidcUser} principal shape
 * production login produces, just skipping the OAuth2 handshake. Playwright's {@link
 * BrowserContext} then carries that session cookie across every subsequent navigation, exactly as
 * a real browser would after a real login.
 *
 * <p>Chromium itself is resolved from the system package the devcontainer installs (via {@code
 * PLAYWRIGHT_CHROMIUM_EXECUTABLE} or the usual {@code /usr/bin/chromium} path) rather than
 * Playwright's own bundled download: this project's Linux distribution isn't one of Playwright's
 * officially supported targets, so its bundled browser build is missing several native libraries
 * here, while the distribution's own {@code chromium}/{@code chromium-sandbox} packages already
 * carry compatible ones. Playwright transparently falls back to its own bundled download when no
 * system Chromium is found (e.g. on an officially-supported CI runner).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(HomepageAccessibilityIT.TestLoginSupport.class)
class HomepageAccessibilityIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @LocalServerPort
    int port;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ParticipantRepository participantRepository;

    @Autowired
    TopicRepository topicRepository;

    @Autowired
    ContentPageRepository contentPageRepository;

    @Autowired
    OrganiserSettingsRepository organiserSettingsRepository;

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

    // --- Homepage (User Story 1, 3, 5) -----------------------------------------------------------

    @Test
    void unregisteredHomepageHasNoCriticalOrSeriousViolations() {
        User user = persistUser(false);
        loginAs(user);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/");
        assertNoSeriousViolations(page, "/ (unregistered)");
    }

    @Test
    void registeredHomepageWithTopicsAndRevokeDialogHasNoCriticalOrSeriousViolations() {
        User user = persistUser(false);
        persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        persistTopic(user.getId(), "Own Approved Topic", TopicApprovalStatus.APPROVED);
        persistTopic(user.getId(), "Own Pending Topic", TopicApprovalStatus.PENDING);
        loginAs(user);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/");
        assertNoSeriousViolations(page, "/ (registered, with topics)");
    }

    // --- Topic propose/edit forms (User Story 3) --------------------------------------------------

    @Test
    void topicProposeFormHasNoCriticalOrSeriousViolations() {
        User user = persistUser(false);
        persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        loginAs(user);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/topics/new");
        assertNoSeriousViolations(page, "/topics/new");
    }

    @Test
    void topicEditFormHasNoCriticalOrSeriousViolations() {
        User user = persistUser(false);
        persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        Topic topic = persistTopic(user.getId(), "Editable Topic", TopicApprovalStatus.APPROVED);
        loginAs(user);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/topics/" + topic.getId() + "/edit");
        assertNoSeriousViolations(page, "/topics/{id}/edit");
    }

    // --- Organiser settings (User Story 2, 4) -----------------------------------------------------

    @Test
    void organiserSettingsHasNoCriticalOrSeriousViolations() {
        User organiser = persistUser(true);
        loginAs(organiser);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/organiser/settings");
        assertNoSeriousViolations(page, "/organiser/settings");
    }

    // --- Content Page management (User Story 6) ---------------------------------------------------

    @Test
    void contentPageManagementHasNoCriticalOrSeriousViolations() {
        User organiser = persistUser(true);
        ContentPage existing = persistContentPage();
        loginAs(organiser);

        Page listPage = context.newPage();
        listPage.navigate(baseUrl() + "/organiser/content-pages");
        assertNoSeriousViolations(listPage, "/organiser/content-pages");

        Page newFormPage = context.newPage();
        newFormPage.navigate(baseUrl() + "/organiser/content-pages/new");
        assertNoSeriousViolations(newFormPage, "/organiser/content-pages/new");

        Page editFormPage = context.newPage();
        editFormPage.navigate(baseUrl() + "/organiser/content-pages/" + existing.getId() + "/edit");
        assertNoSeriousViolations(editFormPage, "/organiser/content-pages/{id}/edit");
    }

    // --- Content Image management (User Story 7) --------------------------------------------------

    @Test
    void contentImageManagementHasNoCriticalOrSeriousViolations() {
        User organiser = persistUser(true);
        loginAs(organiser);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/organiser/content-images");
        assertNoSeriousViolations(page, "/organiser/content-images");
    }

    // --- Info section (User Story 5) ---------------------------------------------------------------

    @Test
    void infoSectionHasNoCriticalOrSeriousViolations() {
        User user = persistUser(false);
        ContentPage infoPage = persistContentPage();
        loginAs(user);

        Page listPage = context.newPage();
        listPage.navigate(baseUrl() + "/info");
        assertNoSeriousViolations(listPage, "/info");

        Page detailPage = context.newPage();
        detailPage.navigate(baseUrl() + "/info/" + infoPage.getId());
        assertNoSeriousViolations(detailPage, "/info/{id}");
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

    private Participant persistParticipant(UUID userId, ParticipantStatus status) {
        Participant participant = new Participant();
        participant.setUserId(userId);
        participant.setStatus(status);
        Instant now = Instant.now();
        participant.setCreatedAt(now);
        participant.setUpdatedAt(now);
        return participantRepository.save(participant).block();
    }

    private Topic persistTopic(UUID creatorUserId, String name, TopicApprovalStatus approvalStatus) {
        Topic topic = new Topic();
        topic.setName(name + " " + UUID.randomUUID());
        topic.setDescription("Description");
        topic.setCreatedByUserId(creatorUserId);
        topic.setApprovalStatus(approvalStatus);
        Instant now = Instant.now();
        topic.setCreatedAt(now);
        topic.setUpdatedAt(now);
        return topicRepository.save(topic).block();
    }

    private ContentPage persistContentPage() {
        ContentPage page = new ContentPage();
        page.setTitle("Scanned Page " + UUID.randomUUID());
        page.setBodyMarkdown("# Heading\n\nSome *content*.");
        page.setSortIndex(0);
        page.setHomepage(false);
        Instant now = Instant.now();
        page.setCreatedAt(now);
        page.setUpdatedAt(now);
        return contentPageRepository.save(page).block();
    }

    /**
     * Test-only pre-authentication backdoor (research.md §9): seeds a real {@link
     * SecurityContext} into the {@code WebSession} for {@code GET /__test-login?userId=...},
     * skipping the OAuth2 handshake entirely — no live IdP is started for this suite. Registered
     * only in this test's {@code ApplicationContext}, never in production.
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

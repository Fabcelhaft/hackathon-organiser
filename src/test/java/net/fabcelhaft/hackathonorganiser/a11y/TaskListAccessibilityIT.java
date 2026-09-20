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
import net.fabcelhaft.hackathonorganiser.event.EventType;
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
import org.springframework.r2dbc.core.DatabaseClient;
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
 * Automated WCAG 2.1 AA scan for the Task list (T047; FR-031, SC-008), matching {@code
 * EventDestinationAccessibilityIT}'s structure.
 *
 * <p>The specific risk here is FR-031's: every row repeats the same three controls, so without a
 * per-row accessible name a screen reader hears "Save, Save, Save" with nothing to say which Task
 * each belongs to. Both the default and done-inclusive views are scanned, because the done view
 * swaps Done for Reopen and adds the cut-off notice.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TaskListAccessibilityIT.TestLoginSupport.class)
class TaskListAccessibilityIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @LocalServerPort
    int port;

    @Autowired
    UserRepository userRepository;

    @Autowired
    DatabaseClient databaseClient;

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

    @Test
    void theTaskListWithRowsHasNoCriticalOrSeriousViolations() {
        User organiser = persistUser(true);
        insertTask("Review new topic: Robot Arm", false, organiser.getId());
        insertTask("Review new topic: Robot Arm", false, null); // identical title, second row
        loginAs(organiser);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/organiser/tasks");
        assertNoSeriousViolations(page, "/organiser/tasks");
    }

    @Test
    void theDoneInclusiveTaskListHasNoCriticalOrSeriousViolations() {
        User organiser = persistUser(true);
        insertTask("Outstanding work", false, null);
        insertTask("Finished work", true, organiser.getId());
        loginAs(organiser);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/organiser/tasks?show=done");
        assertNoSeriousViolations(page, "/organiser/tasks?show=done");
    }

    @Test
    void theEmptyTaskListHasNoCriticalOrSeriousViolations() {
        User organiser = persistUser(true);
        loginAs(organiser);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/organiser/tasks");
        assertNoSeriousViolations(page, "/organiser/tasks (empty)");
    }

    /**
     * FR-031 directly: with two identically-titled rows, each control must still name the Task it
     * acts on, or the page is ambiguous to assistive technology even though axe reports no
     * violation.
     */
    @Test
    void everyRowControlNamesItsOwnTask() {
        User organiser = persistUser(true);
        insertTask("Alpha task", false, null);
        insertTask("Beta task", false, null);
        loginAs(organiser);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/organiser/tasks");

        // Exact matching: getByLabel is substring-based by default, so "Assignee for Alpha task"
        // would otherwise also match the Save button's "Save assignee for Alpha task".
        var exact = new Page.GetByLabelOptions().setExact(true);

        for (String title : List.of("Alpha task", "Beta task")) {
            assertThat(page.getByLabel("Save assignee for " + title, exact).count())
                    .as("Save button naming '%s'", title)
                    .isEqualTo(1);
            assertThat(page.getByLabel("Mark done: " + title, exact).count())
                    .as("Done button naming '%s'", title)
                    .isEqualTo(1);
            assertThat(page.getByLabel("Assignee for " + title, exact).count())
                    .as("Assignee select naming '%s'", title)
                    .isEqualTo(1);
        }
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

    private void insertTask(String title, boolean done, UUID assignee) {
        var spec = databaseClient
                .sql("INSERT INTO tasks (rule_name, event_type, title, assignee_user_id, done, done_at) "
                        + "VALUES (:rn, :et, :title, :uid, :done, :doneAt)")
                .bind("rn", "A11y Rule")
                .bind("et", EventType.TOPIC_PROPOSED.name())
                .bind("title", title)
                .bind("done", done);
        spec = assignee == null ? spec.bindNull("uid", UUID.class) : spec.bind("uid", assignee);
        spec = done ? spec.bind("doneAt", Instant.now()) : spec.bindNull("doneAt", Instant.class);
        spec.then().block();
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

    /**
     * Test-only pre-authentication backdoor — see {@code HomepageAccessibilityIT.TestLoginSupport}
     * for the full rationale; duplicated here rather than shared since each {@code a11y.*IT} class
     * is an independent {@code @SpringBootTest} context.
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

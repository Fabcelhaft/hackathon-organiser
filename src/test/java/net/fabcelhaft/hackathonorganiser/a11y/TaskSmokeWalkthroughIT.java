package net.fabcelhaft.hackathonorganiser.a11y;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
 * Feature 011 (T050) visual smoke walkthrough: drives the real Task Rule form and Task list in a
 * real browser and writes screenshots to {@code target/smoke/}, so the rendered result is inspected
 * rather than inferred — the Thymeleaf visual smoke-test the constitution's Development Workflow
 * item 3 requires before a feature is considered complete. Mirrors {@code SmokeWalkthroughIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TaskSmokeWalkthroughIT.TestLoginSupport.class)
class TaskSmokeWalkthroughIT {

    private static final Path SHOTS = Paths.get("target/smoke");

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
    static void launchBrowser() throws Exception {
        Files.createDirectories(SHOTS);
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
    void theTaskRuleFormAndTaskListRenderCorrectly() {
        User organiser = persistUser("Ada Lovelace", true);
        persistUser("Grace Hopper", true);
        loginAs(organiser);

        Page page = context.newPage();

        // 1. The Event Destination form with Task selected: Kafka/HTTP hidden, Task fields and the
        //    wildcard help text visible, Credential hidden (FR-008, FR-033).
        page.navigate(baseUrl() + "/organiser/event-destinations/new");
        page.selectOption("#type", "TASK");
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(SHOTS.resolve("011-01-task-rule-form.png"))
                .setFullPage(true));
        assertThat(page.locator("#taskFields").isVisible()).isTrue();
        assertThat(page.locator("#kafkaFields").isVisible()).isFalse();
        assertThat(page.locator("#httpFields").isVisible()).isFalse();
        assertThat(page.locator("#credentialField").isVisible()).isFalse();

        // 2. Create the Rule through the real form, then see it listed with its pattern (FR-032).
        page.fill("#name", "Topic review");
        page.fill("#taskTitlePattern", "Review new topic: {{topic.name}}");
        page.selectOption("#taskDefaultAssignee", new com.microsoft.playwright.options.SelectOption()
                .setLabel("Grace Hopper"));
        page.click("form button[type='submit']");
        page.waitForLoadState();
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(SHOTS.resolve("011-02-destination-list-with-task-rule.png"))
                .setFullPage(true));
        assertThat(page.content()).contains("Review new topic: {{topic.name}}");
        assertThat(page.content()).contains("Disabled"); // FR-006

        // 3. The inert marker, once enabled with no Event Types selected (FR-032a).
        page.click("form[action$='/enable'] button");
        page.waitForLoadState();
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(SHOTS.resolve("011-03-inert-marker.png"))
                .setFullPage(true));
        assertThat(page.content()).contains("enabled but inert");

        // 4. The Task list: four columns, timestamps as secondary text, two identical titles
        //    (FR-021, FR-021a).
        insertTask("Review new topic: Robot Arm", false, null, Instant.now().minusSeconds(3600));
        insertTask("Review new topic: Robot Arm", false, organiser.getId(), Instant.now());
        page.navigate(baseUrl() + "/organiser/tasks?cacheBust=" + UUID.randomUUID());
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(SHOTS.resolve("011-04-task-list-default.png"))
                .setFullPage(true));
        assertThat(page.locator("table.task-table thead th").count()).isEqualTo(4);
        assertThat(page.locator("table.task-table tbody tr").count()).isEqualTo(2);

        // 5. Assign and save through the real controls (FR-024).
        page.locator("select[aria-label^='Assignee for']")
                .first()
                .selectOption(new com.microsoft.playwright.options.SelectOption().setLabel("Ada Lovelace"));
        page.locator("button[aria-label^='Save assignee for']").first().click();
        page.waitForLoadState();
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(SHOTS.resolve("011-05-task-list-after-save.png"))
                .setFullPage(true));

        // 6. Done, then the done-inclusive view showing completion time and Reopen (FR-023, FR-026).
        page.locator("button[aria-label^='Mark done:']").first().click();
        page.waitForLoadState();
        page.click("a[href*='show=done']");
        page.waitForLoadState();
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(SHOTS.resolve("011-06-task-list-show-done.png"))
                .setFullPage(true));
        assertThat(page.content()).contains("completed ");
        assertThat(page.locator("button[aria-label^='Reopen:']").count()).isEqualTo(1);

        // 7. The empty state (FR-029).
        databaseClient.sql("DELETE FROM tasks").then().block();
        page.navigate(baseUrl() + "/organiser/tasks?cacheBust=" + UUID.randomUUID());
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(SHOTS.resolve("011-07-task-list-empty.png"))
                .setFullPage(true));
        assertThat(page.content()).contains("No outstanding Tasks");
    }

    // --- Test support --------------------------------------------------------------------------

    private void insertTask(String title, boolean done, UUID assignee, Instant createdAt) {
        var spec = databaseClient
                .sql("INSERT INTO tasks (rule_name, event_type, title, assignee_user_id, done, done_at, created_at) "
                        + "VALUES (:rn, :et, :title, :uid, :done, :doneAt, :createdAt)")
                .bind("rn", "Topic review")
                .bind("et", EventType.TOPIC_PROPOSED.name())
                .bind("title", title)
                .bind("done", done)
                .bind("createdAt", createdAt);
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

    private User persistUser(String displayName, boolean organiser) {
        User user = new User();
        user.setOidcSubject("sub-" + UUID.randomUUID());
        user.setDisplayName(displayName);
        user.setEmail(displayName.toLowerCase().replace(' ', '.') + "@example.com");
        user.setOrganiser(organiser);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).block();
    }

    /** Test-only pre-authentication backdoor — see {@code SmokeWalkthroughIT.TestLoginSupport}. */
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

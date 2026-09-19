package net.fabcelhaft.hackathonorganiser.a11y;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.FilePayload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 * Feature 010 (T043) visual smoke walkthrough: drives the real pages in a real browser and writes
 * screenshots to {@code target/smoke/}, so the rendered result is inspected rather than inferred —
 * Constitution Development Workflow #3. Uses the same Playwright + {@code __test-login} harness the
 * {@code a11y.*IT} suite already established; the Dex login flow is skipped because this feature
 * touches no part of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(SmokeWalkthroughIT.TestLoginSupport.class)
class SmokeWalkthroughIT {

    private static final Path SHOTS = Paths.get("target/smoke");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @LocalServerPort
    int port;

    @Autowired
    UserRepository userRepository;

    @Autowired
    TopicRepository topicRepository;

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
        context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1100, 1400));
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void walkThroughDescriptionRenderingAndAttachmentManagement() {
        User author = persistUser(false);
        Topic topic = persistTopic(
                author.getId(),
                "Autonomous Delivery Robot",
                """
                # What we're building

                A small delivery robot for the campus, built over the weekend.

                ## What we need

                - someone who enjoys **embedded C**
                - a designer for the shell
                - anyone curious about `ROS2`

                > No prior robotics experience required.

                Background reading lives at https://example.org/robotics-primer. See also
                [the parts list](https://example.org/parts).
                """);
        loginAs(author);

        // 1. Detail view: rendered markdown under the heading, empty attachments state.
        Page page = context.newPage();
        page.navigate(baseUrl() + "/topics/" + topic.getId());
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(SHOTS.resolve("01-detail-rendered-description.png"))
                .setFullPage(true));
        assertThat(page.locator("section.topic-description h2").first().textContent())
                .contains("What we're building");

        // 2. Edit screen: markdown hint + attachments section with its upload form.
        page.navigate(baseUrl() + "/topics/" + topic.getId() + "/edit");
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(SHOTS.resolve("02-edit-hint-and-attachments.png"))
                .setFullPage(true));

        // 3. Upload a real file through the form.
        page.setInputFiles(
                "#file",
                new FilePayload(
                        "parts-list.pdf", "application/pdf", "%PDF-1.4 fake pdf bytes".getBytes()));
        page.click("#attachments form[enctype='multipart/form-data'] button[type='submit']");
        page.waitForLoadState();
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(SHOTS.resolve("03-edit-after-upload.png"))
                .setFullPage(true));
        assertThat(page.content()).contains("parts-list.pdf");

        // 4. Rejected upload: message plus a still-populated form.
        page.setInputFiles(
                "#file", new FilePayload("payload.exe", "application/pdf", "MZ".getBytes()));
        page.click("#attachments form[enctype='multipart/form-data'] button[type='submit']");
        page.waitForLoadState();
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(SHOTS.resolve("04-edit-rejected-upload.png"))
                .setFullPage(true));

        // 5. Detail view again, now listing the attachment. Chromium would otherwise serve this
        // URL from its own cache (it was visited in step 1, and the page sets no cache headers),
        // producing a screenshot identical to step 1 that hides what changed.
        page.navigate(baseUrl() + "/topics/" + topic.getId() + "?cacheBust=" + UUID.randomUUID());
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(SHOTS.resolve("05-detail-with-attachment.png"))
                .setFullPage(true));
        assertThat(page.content()).contains("parts-list.pdf");

        // 6. Propose form: hint present, no upload control.
        page.navigate(baseUrl() + "/topics/new");
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(SHOTS.resolve("06-propose-form.png"))
                .setFullPage(true));
    }

    @Test
    void walkThroughOrganiserDetailAndAuditTrail() {
        User organiser = persistUser(true);
        Topic topic = persistTopic(
                organiser.getId(),
                "Organiser View Topic",
                "# Heading\n\n- a point\n\nLinks like https://example.org/handbook are clickable.");
        loginAs(organiser);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/organiser/topics/" + topic.getId() + "/edit");
        page.setInputFiles(
                "#file",
                new FilePayload("agenda.txt", "text/plain", "09:00 kickoff".getBytes()));
        page.click("#attachments form[enctype='multipart/form-data'] button[type='submit']");
        page.waitForLoadState();

        page.navigate(baseUrl() + "/organiser/topics/" + topic.getId());
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(SHOTS.resolve("07-organiser-detail.png"))
                .setFullPage(true));

        page.navigate(baseUrl() + "/organiser/topics/" + topic.getId() + "/audit");
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(SHOTS.resolve("08-organiser-audit.png"))
                .setFullPage(true));
        assertThat(page.content()).contains("agenda.txt").doesNotContain("null -&gt;");
    }

    // --- Test support --------------------------------------------------------------------------

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private void loginAs(User user) {
        Page loginPage = context.newPage();
        loginPage.navigate(baseUrl() + "/__test-login?userId=" + user.getId());
        loginPage.close();
    }

    private User persistUser(boolean organiser) {
        User user = new User();
        user.setOidcSubject("sub-" + UUID.randomUUID());
        user.setDisplayName("Robin Fields");
        user.setEmail("user-" + UUID.randomUUID() + "@example.com");
        user.setOrganiser(organiser);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).block();
    }

    private Topic persistTopic(UUID creatorUserId, String name, String description) {
        Topic topic = new Topic();
        topic.setName(name);
        topic.setDescription(description);
        topic.setCreatedByUserId(creatorUserId);
        topic.setApprovalStatus(TopicApprovalStatus.APPROVED);
        Instant now = Instant.now();
        topic.setCreatedAt(now);
        topic.setUpdatedAt(now);
        return topicRepository.save(topic).block();
    }

    private static Path resolveSystemChromium() {
        for (String candidate : List.of(
                "/usr/bin/chromium", "/usr/bin/chromium-browser", "/usr/bin/google-chrome")) {
            Path path = Paths.get(candidate);
            if (Files.exists(path)) {
                return path;
            }
        }
        return null;
    }

    @TestConfiguration
    static class TestLoginSupport {

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        org.springframework.web.server.WebFilter smokeTestLoginFilter(UserRepository userRepository) {
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

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
import net.fabcelhaft.hackathonorganiser.audit.AuditActor;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
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
 * Automated WCAG 2.1 AA scan for the Topic Overview (T066; SC-008; research.md §9), including its
 * Compliance status badges — reusing 003's Playwright + axe-core suite exactly the way {@code
 * HomepageAccessibilityIT} already established (research.md §9, "reused a third time").
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TopicOverviewAccessibilityIT.TestLoginSupport.class)
class TopicOverviewAccessibilityIT {

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
    GroupService groupService;

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
    void topicOverviewWithNoGroupYetHasNoCriticalOrSeriousViolations() {
        User author = persistUser(false);
        persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        persistTopic(author.getId(), "No Group Overview Topic");
        loginAs(author);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/topics/overview");
        assertNoSeriousViolations(page, "/topics/overview (No Group Yet)");
    }

    @Test
    void topicOverviewWithAComplianceBadgeHasNoCriticalOrSeriousViolations() {
        User author = persistUser(false);
        Participant participant = persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        Topic topic = persistTopic(author.getId(), "Compliant Overview Topic");
        groupService
                .create(topic.getId(), List.of(participant.getId()), new AuditActor(author.getId(), false))
                .block();
        loginAs(author);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/topics/overview");
        assertNoSeriousViolations(page, "/topics/overview (with Compliance badge)");
    }

    @Test
    void topicOverviewWithPinnedOwnTopicAndAJoinActionHasNoCriticalOrSeriousViolations() {
        // Feature 005 (US5, US9, US10): the viewer's own Topic pinned above the rest, plus a
        // rendered "Join" action and "View Details" link on the same row.
        User author = persistUser(false);
        persistParticipant(author.getId(), ParticipantStatus.ACTIVE);
        persistTopic(author.getId(), "Own Pinned Overview Topic");
        User viewer = persistUser(false);
        persistParticipant(viewer.getId(), ParticipantStatus.ACTIVE);
        loginAs(viewer);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/topics/overview");
        assertNoSeriousViolations(page, "/topics/overview (pinned own Topic + Join + View Details)");
    }

    @Test
    void topicOverviewWithUpvoteControlsAndAReferenceNumberHasNoCriticalOrSeriousViolations() {
        // Feature 012 (US1, US2): the toggling Upvote/Withdraw-upvote control and a Topic's
        // reference number badge, both rendered on the same row.
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), "Overview Upvoted Topic");
        topic.setReferenceNumber(1);
        topicRepository.save(topic).block();
        User voter = persistUser(false);
        castUpvote(topic.getId(), voter.getId());
        loginAs(voter);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/topics/overview");
        assertNoSeriousViolations(page, "/topics/overview (upvote control + reference number)");
    }

    // Feature 013 (FR-001, FR-003, FR-004, FR-013): navigation moved onto the Topic name, so the
    // name now has to behave like the control it became — focusable, visibly focused, and with its
    // badges beside it rather than swallowed into the link text.
    @Test
    void theTopicNameLinkIsKeyboardReachableAndItsBadgesStayOutsideTheLink() {
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), "Overview Name Link Topic");
        topic.setReferenceNumber(7);
        topicRepository.save(topic).block();
        loginAs(author);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/topics/overview");

        var link = page.locator("a[href='/topics/" + topic.getId() + "']").first();
        assertThat(link.count()).isEqualTo(1);
        assertThat(link.textContent().trim())
                .withFailMessage("FR-004: the badge must not be part of the link text")
                .isEqualTo(topic.getName());

        link.focus();
        assertThat(page.evaluate("() => document.activeElement.tagName")).isEqualTo("A");
        // Pico draws focus with an outline/box-shadow; either satisfies FR-003's "visible
        // indicator", and asserting neither is absent is what catches an outline:none regression.
        Object focusStyle = page.evaluate(
                "() => { const s = getComputedStyle(document.activeElement);"
                        + " return s.outlineStyle + '|' + s.boxShadow; }");
        assertThat(String.valueOf(focusStyle))
                .withFailMessage("FR-003: the focused name link must show a visible focus indicator")
                .doesNotStartWith("none|none");

        RowLayoutAssertions.assertNoStackedRowControls(page, "/topics/overview (name link)");
        assertNoSeriousViolations(page, "/topics/overview (name as link)");
    }

    // Feature 013 (FR-012a, SC-001a): the first cut of this feature kept Topic names on one line
    // and let the table scroll sideways. Running it showed the cost — Compliance, Upvotes and Join
    // slid off the right-hand edge, where a reader has no cue they exist. A two-line title is the
    // cheaper trade. This asserts the table is never wider than the viewport that contains it, at
    // the width where a long title would previously have forced the overflow.
    @Test
    void theOverviewNeverScrollsSidewaysEvenWithAVeryLongTopicName() {
        User author = persistUser(false);
        persistTopic(author.getId(),
                "Open Mic Pitch make sure that the organiser@example.dev user from dex also has organiser rightsNight");
        loginAs(author);

        Page page = context.newPage();
        page.setViewportSize(1024, 800);
        page.navigate(baseUrl() + "/topics/overview");

        Object overflow = page.evaluate(
                "() => { const t = document.querySelector('table');"
                        + " const widest = [...t.querySelectorAll('thead th')].map((th, i) =>"
                        + "   th.textContent.trim() + '=' + Math.round(th.getBoundingClientRect().width))"
                        + "   .join(', ');"
                        + " return { scrollW: t.scrollWidth, clientW: t.clientWidth,"
                        + "          docScroll: document.documentElement.scrollWidth,"
                        + "          docClient: document.documentElement.clientWidth,"
                        + "          columns: widest }; }");
        var m = (java.util.Map<String, Object>) overflow;
        int tableScroll = ((Number) m.get("scrollW")).intValue();
        int tableClient = ((Number) m.get("clientW")).intValue();
        int docScroll = ((Number) m.get("docScroll")).intValue();
        int docClient = ((Number) m.get("docClient")).intValue();

        assertThat(tableScroll)
                .withFailMessage("FR-012a: the overview table must fit its container, not scroll"
                        + " sideways (scrollWidth=%d, clientWidth=%d)", tableScroll, tableClient)
                .isLessThanOrEqualTo(tableClient);
        assertThat(docScroll)
                .withFailMessage("FR-012a: the page itself must not overflow sideways either"
                        + " (scrollWidth=%d, clientWidth=%d). Column widths: %s",
                        docScroll, docClient, m.get("columns"))
                .isLessThanOrEqualTo(docClient);

        // FR-012b: wrapping is for text. The controls must still share one line.
        RowLayoutAssertions.assertNoStackedRowControls(page, "/topics/overview (long name, wrapped)");
    }

    // --- Test support --------------------------------------------------------------------------

    private void castUpvote(UUID topicId, UUID userId) {
        databaseClient
                .sql("INSERT INTO topic_upvotes (topic_id, user_id) VALUES (:tid, :uid)")
                .bind("tid", topicId)
                .bind("uid", userId)
                .then()
                .block();
    }

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

    private Topic persistTopic(UUID creatorUserId, String name) {
        Topic topic = new Topic();
        topic.setName(name + " " + UUID.randomUUID());
        topic.setDescription("Description");
        topic.setCreatedByUserId(creatorUserId);
        topic.setApprovalStatus(TopicApprovalStatus.APPROVED);
        Instant now = Instant.now();
        topic.setCreatedAt(now);
        topic.setUpdatedAt(now);
        return topicRepository.save(topic).block();
    }

    /**
     * Test-only pre-authentication backdoor (research.md §9) — see {@code
     * HomepageAccessibilityIT.TestLoginSupport} for the full rationale; duplicated here rather than
     * shared since each {@code a11y.*IT} class is an independent {@code @SpringBootTest} context.
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

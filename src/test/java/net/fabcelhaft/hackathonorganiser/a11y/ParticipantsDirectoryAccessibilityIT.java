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
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinition;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinitionRepository;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldType;
import net.fabcelhaft.hackathonorganiser.organisersettings.DirectoryAudience;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsRepository;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantRepository;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
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
 * Automated WCAG 2.1 AA scan for SC-009 (T059; research.md §7): the Participants directory table,
 * a Participant's own detail view ({@code GET /profile}), another Participant's detail view, and
 * the extended Organiser settings/Custom Field forms. Per FR-037's scope note, 002/003's
 * pre-existing organiser screens are intentionally excluded — only the new/extended controls added
 * by this feature are in scope.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(ParticipantsDirectoryAccessibilityIT.TestLoginSupport.class)
class ParticipantsDirectoryAccessibilityIT {

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
    CustomFieldDefinitionRepository customFieldDefinitionRepository;

    @Autowired
    OrganiserSettingsRepository organiserSettingsRepository;

    @Autowired
    org.springframework.r2dbc.core.DatabaseClient databaseClient;

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

    @BeforeEach
    void resetOrganiserSettings() {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setParticipantsDirectoryAudience(DirectoryAudience.ALL_AUTHENTICATED);
                    settings.setSkillVisibilityEnabled(true);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    // --- Directory table and detail views (User Story 5) --------------------------------------

    @Test
    void directoryTableHasNoCriticalOrSeriousViolations() {
        User viewer = persistUser(false);
        User other = persistUser(false);
        CustomFieldDefinition overviewField =
                persistDefinition("T-Shirt Size", CustomFieldType.FREE_TEXT, false, false, true);
        Participant participant = persistParticipant(other.getId(), ParticipantStatus.ACTIVE);
        insertFreeTextValue(participant.getId(), overviewField.getId(), "Medium");
        loginAs(viewer);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/participants");
        assertNoSeriousViolations(page, "/participants");
    }

    @Test
    void ownDetailViewHasNoCriticalOrSeriousViolations() {
        User user = persistUser(false);
        persistParticipant(user.getId(), ParticipantStatus.ACTIVE);
        persistDefinition("T-Shirt Size", CustomFieldType.FREE_TEXT, false, true, false);
        loginAs(user);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/profile");
        assertNoSeriousViolations(page, "/profile");
    }

    @Test
    void anotherParticipantsDetailViewHasNoCriticalOrSeriousViolations() {
        User viewer = persistUser(false);
        User other = persistUser(false);
        CustomFieldDefinition publicField = persistDefinition("Bio", CustomFieldType.FREE_TEXT, false, true, false);
        Participant participant = persistParticipant(other.getId(), ParticipantStatus.ACTIVE);
        insertFreeTextValue(participant.getId(), publicField.getId(), "Loves hackathons");
        loginAs(viewer);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/participants/" + participant.getId());
        assertNoSeriousViolations(page, "/participants/{id} (other viewer)");
    }

    // --- Extended organiser settings and Custom Field forms (User Story 2, 3) ------------------

    @Test
    void extendedOrganiserSettingsFormHasNoCriticalOrSeriousViolations() {
        User organiser = persistUser(true);
        loginAs(organiser);

        Page page = context.newPage();
        page.navigate(baseUrl() + "/organiser/settings");
        assertNoSeriousViolations(page, "/organiser/settings (extended)");
    }

    @Test
    void extendedCustomFieldFormsHaveNoCriticalOrSeriousViolations() {
        User organiser = persistUser(true);
        loginAs(organiser);

        Page listPage = context.newPage();
        listPage.navigate(baseUrl() + "/organiser/custom-fields");
        assertNoSeriousViolations(listPage, "/organiser/custom-fields (extended list)");

        Page newFormPage = context.newPage();
        newFormPage.navigate(baseUrl() + "/organiser/custom-fields/new");
        assertNoSeriousViolations(newFormPage, "/organiser/custom-fields/new (extended)");
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

    private CustomFieldDefinition persistDefinition(
            String label, CustomFieldType type, boolean required, boolean public_, boolean overview) {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setLabel(label);
        definition.setFieldType(type);
        definition.setRequired(required);
        definition.setPublic_(public_);
        definition.setOverview(overview);
        definition.setEnabled(true);
        Instant now = Instant.now();
        definition.setCreatedAt(now);
        definition.setUpdatedAt(now);
        return customFieldDefinitionRepository.save(definition).block();
    }

    private void insertFreeTextValue(UUID participantId, UUID definitionId, String value) {
        databaseClient
                .sql(
                        "INSERT INTO custom_field_values"
                                + " (participant_id, custom_field_definition_id, free_text_value)"
                                + " VALUES (:pid, :fid, :value)")
                .bind("pid", participantId)
                .bind("fid", definitionId)
                .bind("value", value)
                .then()
                .block();
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

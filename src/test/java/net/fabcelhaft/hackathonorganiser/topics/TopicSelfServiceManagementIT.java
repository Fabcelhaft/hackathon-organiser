package net.fabcelhaft.hackathonorganiser.topics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.audit.AuditActor;
import net.fabcelhaft.hackathonorganiser.content.ContentPage;
import net.fabcelhaft.hackathonorganiser.content.ContentPageContext;
import net.fabcelhaft.hackathonorganiser.content.ContentPageRepository;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsRepository;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantRepository;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.skill.Skill;
import net.fabcelhaft.hackathonorganiser.skill.SkillRepository;
import net.fabcelhaft.hackathonorganiser.topic.Topic;
import net.fabcelhaft.hackathonorganiser.topic.TopicApprovalStatus;
import net.fabcelhaft.hackathonorganiser.topic.TopicRepository;
import net.fabcelhaft.hackathonorganiser.topic.TopicService;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.r2dbc.core.DatabaseClient;
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

/**
 * Integration tests for User Story 3's Topic browse/propose/edit self-service (T021;
 * contracts/topics-self-service-and-approval.md): the homepage topic list shows name, description,
 * and author display-name+OIDC-subject (FR-009); propose creates a Topic authored by the current
 * Participant, starting Pending/Approved per the current setting (FR-013); any authenticated
 * Standard user may propose, regardless of Participant status; the author can edit their own
 * Topic, a non-author cannot (FR-011); the author's
 * own Pending Topic sorts to the top labeled "Pending approval" (FR-009a, FR-012b); a Pending
 * Topic is invisible to any other non-Organiser viewer (FR-012a); a blank field re-renders the
 * propose form with a field-associated error (FR-037).
 */
@SpringBootTest
@Testcontainers
class TopicSelfServiceManagementIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @Autowired
    ApplicationContext applicationContext;

    WebTestClient webTestClient;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ParticipantRepository participantRepository;

    @Autowired
    TopicRepository topicRepository;

    @Autowired
    OrganiserSettingsRepository organiserSettingsRepository;

    @Autowired
    SkillRepository skillRepository;

    @Autowired
    TopicService topicService;

    @Autowired
    ContentPageRepository contentPageRepository;

    @Autowired
    DatabaseClient databaseClient;

    @BeforeEach
    void setUpWebTestClient() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    /**
     * The Home Page's fullness-sorted rows and own-Topic pinning (Story 10) are both computed over
     * every Topic in the database — without this cleanup, Topics accumulated from earlier tests in
     * this class would compete for the 10-row cap, mirroring the same fix already applied to {@code
     * HomeControllerIT}. Deleted in FK-dependency order (no {@code ON DELETE CASCADE} in schema.sql).
     */
    @BeforeEach
    void resetTopicsAndGroupsBetweenTests() {
        // topic_attachments cascades from topics, but is deleted explicitly so the per-Topic count
        // assertions in this class never see rows left by an earlier test.
        databaseClient.sql("DELETE FROM topic_attachments").then().block();
        databaseClient.sql("DELETE FROM group_members").then().block();
        databaseClient.sql("DELETE FROM groups").then().block();
        databaseClient.sql("DELETE FROM topic_skills").then().block();
        databaseClient.sql("DELETE FROM topics").then().block();
    }

    @BeforeEach
    void resetOrganiserSettingsToDefaults() {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setSelfRegistrationEnabled(true);
                    settings.setSelfRevocationEnabled(true);
                    settings.setTopicApprovalRequired(false);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    @Test
    void homepageTopicTableShowsNameAndParticipantCount() {
        // Feature 005 (FR-003, FR-004): the Home Page table shows Name/participant count/Skills
        // you offer only — author and description moved to GET /topics/overview (US5).
        User author = persistUser(false);
        persistParticipant(author.getId());
        Topic topic = persistTopic(author.getId(), "Robotics", "Build a robot", TopicApprovalStatus.APPROVED);

        String body = homeBody(author);

        assertThat(body).contains(topic.getName());
    }

    @Test
    void proposeCreatesATopicAuthoredByTheCurrentParticipantStartingApprovedWhenApprovalNotRequired() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        String name = "Proposed Topic " + UUID.randomUUID();

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics")
                .body(BodyInserters.fromFormData("name", name).with("description", "A description"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        Topic saved = findByName(name);
        assertThat(saved.getCreatedByUserId()).isEqualTo(author.getId());
        assertThat(saved.getApprovalStatus()).isEqualTo(TopicApprovalStatus.APPROVED);
    }

    @Test
    void proposeStartsPendingWhenTopicApprovalIsRequired() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        enableTopicApprovalRequired();
        String name = "Pending Proposed Topic " + UUID.randomUUID();

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics")
                .body(BodyInserters.fromFormData("name", name).with("description", "A description"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(findByName(name).getApprovalStatus()).isEqualTo(TopicApprovalStatus.PENDING);
    }

    @Test
    void standardUserWithNoParticipantRecordCanProposeTopic() {
        User standardUser = persistUser(false);
        String name = "No Participant Record Topic " + UUID.randomUUID();

        webTestClient
                .mutateWith(loginAs(standardUser))
                .get()
                .uri("/topics/new")
                .exchange()
                .expectStatus()
                .isOk();

        webTestClient
                .mutateWith(loginAs(standardUser))
                .post()
                .uri("/topics")
                .body(BodyInserters.fromFormData("name", name).with("description", "A description"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(findByName(name).getCreatedByUserId()).isEqualTo(standardUser.getId());
    }

    @Test
    void authorCanEditTheirOwnTopicButANonAuthorCannot() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        User otherUser = persistUser(false);
        persistParticipant(otherUser.getId());
        Topic topic = persistTopic(author.getId(), "Old Name", "Old Desc", TopicApprovalStatus.APPROVED);

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics/{id}", topic.getId())
                .body(BodyInserters.fromFormData("name", "New Name").with("description", "New Desc"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        Topic updated = topicRepository.findById(topic.getId()).block();
        assertThat(updated.getName()).isEqualTo("New Name");
        assertThat(updated.getDescription()).isEqualTo("New Desc");

        webTestClient
                .mutateWith(loginAs(otherUser))
                .get()
                .uri("/topics/{id}/edit", topic.getId())
                .exchange()
                .expectStatus()
                .isForbidden();

        webTestClient
                .mutateWith(loginAs(otherUser))
                .post()
                .uri("/topics/{id}", topic.getId())
                .body(BodyInserters.fromFormData("name", "Hijacked").with("description", "Hijacked"))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void aPendingTopicNeverAppearsOnTheHomePageForAnyoneOtherThanItsOwnAuthor() {
        // Feature 005 (spec Assumptions, updated 2026-08-30 — FR-033, Story 10): a Pending Topic is
        // still invisible on the Home Page to everyone except its author, but its author now sees
        // it pinned above the fullness-sorted rows (see
        // aPendingTopicIsPinnedAboveTheFullnessSortedRowsForItsOwnAuthorButNeverShowsAJoinAction
        // below) — it just never appears there for anyone else, mirroring GET /topics/overview's
        // existing Pending-visibility rule.
        User author = persistUser(false);
        persistParticipant(author.getId());
        User otherViewer = persistUser(false);
        persistParticipant(otherViewer.getId());
        Topic pending = persistTopic(author.getId(), "My Pending Topic", "Desc", TopicApprovalStatus.PENDING);

        assertThat(homeBody(otherViewer)).doesNotContain(pending.getName());
    }

    @Test
    void aPendingTopicIsPinnedAboveTheFullnessSortedRowsForItsOwnAuthorButNeverShowsAJoinAction() {
        // FR-033, FR-035, Story 10: the author's own Pending Topic is pinned above the
        // fullness-sorted rows on the Home Page, but — unlike an Approved pinned Topic — it never
        // offers a "Join" action, since a Pending Topic cannot be joined.
        User author = persistUser(false);
        persistParticipant(author.getId());
        Topic pending = persistTopic(author.getId(), "My Pending Topic", "Desc", TopicApprovalStatus.PENDING);

        String body = homeBody(author);

        assertThat(body).contains(pending.getName());
        assertThat(body).doesNotContain("/topics/" + pending.getId() + "/join");
    }

    @Test
    void pendingTopicIsInvisibleToAnyOtherNonOrganiserViewer() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        User otherViewer = persistUser(false);
        persistParticipant(otherViewer.getId());
        Topic pending =
                persistTopic(author.getId(), "Hidden Pending Topic " + UUID.randomUUID(), "Desc", TopicApprovalStatus.PENDING);

        String body = homeBody(otherViewer);
        assertThat(body).doesNotContain(pending.getName());

        webTestClient
                .mutateWith(loginAs(otherViewer))
                .get()
                .uri("/topics/{id}/edit", pending.getId())
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void proposeWithBlankNameOrDescriptionReRendersTheFormWithAFieldAssociatedError() {
        User author = persistUser(false);
        persistParticipant(author.getId());

        String body = webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics")
                .body(BodyInserters.fromFormData("name", "").with("description", "Some description"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("required");
    }

    // --- Skill selections on propose/edit (Story 1, FR-001, FR-002) -----------------------------

    @Test
    void proposeWithSkillIdsCreatesTheTopicWithExactlyThoseSkillsAttached() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        Skill python = persistSkill("Python");
        Skill rust = persistSkill("Rust");
        String name = "Skilled Topic " + UUID.randomUUID();

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics")
                .body(BodyInserters.fromFormData("name", name)
                        .with("description", "A description")
                        .with("skillIds", python.getId().toString())
                        .with("skillIds", rust.getId().toString()))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        Topic saved = findByName(name);
        String editBody = webTestClient
                .mutateWith(loginAs(author))
                .get()
                .uri("/topics/{id}/edit", saved.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(editBody).containsPattern("(?s)value=\"" + python.getId() + "\".*?checked=\"checked\"");
        assertThat(editBody).containsPattern("(?s)value=\"" + rust.getId() + "\".*?checked=\"checked\"");
    }

    @Test
    void proposeWithNoSkillIdsSucceedsWithAnEmptySkillList() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        String name = "No Skill Topic " + UUID.randomUUID();

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics")
                .body(BodyInserters.fromFormData("name", name).with("description", "A description"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        Topic saved = findByName(name);
        assertThat(topicService.findDetail(saved.getId()).block().skillIds()).isEmpty();
    }

    @Test
    void proposeWithAnUnknownSkillIdIsRejectedAndPreservesTheSubmittedSelection() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        Skill python = persistSkill("Python " + UUID.randomUUID());
        UUID unknownSkillId = UUID.randomUUID();

        String body = webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics")
                .body(BodyInserters.fromFormData("name", "Unknown Skill Topic")
                        .with("description", "A description")
                        .with("skillIds", python.getId().toString())
                        .with("skillIds", unknownSkillId.toString()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).containsPattern("(?s)value=\"" + python.getId() + "\".*?checked=\"checked\"");
    }

    @Test
    void updateReplacesTheSkillSetAddingAndRemoving() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        Skill python = persistSkill("Python " + UUID.randomUUID());
        Skill rust = persistSkill("Rust " + UUID.randomUUID());
        Topic topic = persistTopic(author.getId(), "Old Name", "Old Desc", TopicApprovalStatus.APPROVED);
        topicService
                .updateAsAuthor(
                        topic.getId(),
                        author.getId(),
                        "Old Name",
                        "Old Desc",
                        List.of(python.getId()),
                        new AuditActor(author.getId(), false))
                .block();

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics/{id}", topic.getId())
                .body(BodyInserters.fromFormData("name", "New Name")
                        .with("description", "New Desc")
                        .with("skillIds", rust.getId().toString()))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        List<UUID> skillIds = topicService.findDetail(topic.getId()).block().skillIds();
        assertThat(skillIds).containsExactly(rust.getId());
    }

    // --- Feature 008: TOPIC_CREATION-designated content (FR-015, FR-016b, FR-016c, FR-017, FR-018b) ---

    @Test
    void proposeFormRendersTheDesignatedPagesBodyAboveTheFieldsButTheEditFormNeverDoes() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        Topic own = persistTopic(author.getId(), "Own Topic", "Desc", TopicApprovalStatus.APPROVED);
        undesignate(ContentPageContext.TOPIC_CREATION);
        String marker = "Topic guidance " + UUID.randomUUID();
        ContentPage designated = persistPage("Hidden Title " + UUID.randomUUID(),
                "# How to propose\n\n" + marker + " with *emphasis*.", 0, ContentPageContext.TOPIC_CREATION);

        String newBody = webTestClient
                .mutateWith(loginAs(author))
                .get()
                .uri("/topics/new")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(newBody).contains(marker);
        assertThat(newBody).contains("<em>emphasis</em>");
        assertThat(newBody).contains("<h2>How to propose</h2>");
        assertThat(newBody).doesNotContain(designated.getTitle());
        assertThat(newBody.indexOf(marker)).isLessThan(newBody.indexOf("<form"));
        assertThat(newBody.split("<h1[ >]").length - 1).isEqualTo(1);
        assertThat(newBody).contains("<h1>Propose Topic</h1>");

        String editBody = webTestClient
                .mutateWith(loginAs(author))
                .get()
                .uri("/topics/{id}/edit", own.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(editBody).doesNotContain(marker);
    }

    @Test
    void proposeFormRendersUnchangedWhenNoPageIsDesignatedForTopicCreation() {
        User author = persistUser(false);
        undesignate(ContentPageContext.TOPIC_CREATION);
        String marker = "Should not render " + UUID.randomUUID();
        persistPage("Registration Not Topic " + UUID.randomUUID(), marker, 0, ContentPageContext.USER_REGISTRATION);
        undesignate(ContentPageContext.USER_REGISTRATION);

        String body = webTestClient
                .mutateWith(loginAs(author))
                .get()
                .uri("/topics/new")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).doesNotContain(marker);
        assertThat(body).doesNotContain("designated-content");
        assertThat(body).contains("<form");
    }

    @Test
    void proposeFormFallsBackToNoDesignatedContentAfterTheDesignatedPageIsDeleted() {
        User author = persistUser(false);
        undesignate(ContentPageContext.TOPIC_CREATION);
        String marker = "Soon deleted " + UUID.randomUUID();
        ContentPage designated = persistPage("Doomed " + UUID.randomUUID(), marker, 0, ContentPageContext.TOPIC_CREATION);
        contentPageRepository.delete(designated).block();

        String body = webTestClient
                .mutateWith(loginAs(author))
                .get()
                .uri("/topics/new")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).doesNotContain(marker);
        assertThat(body).contains("<form");
    }

    // --- Test helpers ------------------------------------------------------------------------------

    private void undesignate(ContentPageContext context) {
        contentPageRepository
                .findByContext(context)
                .flatMap(page -> {
                    page.setContext(ContentPageContext.NONE);
                    return contentPageRepository.save(page);
                })
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

    private String homeBody(User user) {
        return webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private Topic findByName(String name) {
        return topicRepository.findAll().filter(t -> t.getName().equals(name)).blockFirst();
    }

    private void enableTopicApprovalRequired() {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setTopicApprovalRequired(true);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
    }

    // --- Feature 010 User Story 1: markdown descriptions (T003) --------------------------------

    @Test
    void topicDetailRendersTheDescriptionAsMarkdownInItsOwnSectionAndNotInTheInfoTable() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        String markdown = "# Overview\n\n- first\n- second\n\n**bold** and `code`\n\n> quoted\n\n"
                + "[explicit](https://example.com) and bare https://example.org/docs.";
        Topic topic = persistTopic(author.getId(), "Markdown Topic", markdown, TopicApprovalStatus.APPROVED);

        String body = detailBody(author, topic.getId());

        assertThat(body).contains("class=\"topic-description\"");
        assertThat(body).contains("<h2>Overview</h2>");
        assertThat(body).contains("<li>first</li>").contains("<li>second</li>");
        assertThat(body).contains("<strong>bold</strong>").contains("<code>code</code>");
        assertThat(body).contains("<blockquote>");
        assertThat(body).contains("href=\"https://example.com\"");
        assertThat(body).contains("href=\"https://example.org/docs\"");
        // FR-001a: the description appears exactly once — the Topic Info row is gone.
        assertThat(body).doesNotContain("<th scope=\"row\">Description</th>");
        // FR-007/SC-008: rendering is display-only; storage is untouched.
        assertThat(topicRepository.findById(topic.getId()).block().getDescription())
                .isEqualTo(markdown);
    }

    @Test
    void topicDetailKeepsTheTopicNameAsTheOnlyTopLevelHeading() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        Topic topic =
                persistTopic(author.getId(), "Heading Topic", "# Author Heading", TopicApprovalStatus.APPROVED);

        String body = detailBody(author, topic.getId());

        assertThat(body).contains("<h2>Author Heading</h2>");
        assertThat(countOccurrences(body, "<h1")).isEqualTo(1);
    }

    @Test
    void topicDetailStripsUnsafeMarkupFromTheDescription() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        String hostile = "Hello<script>alert('xss')</script>"
                + "<img src=\"x\" onerror=\"alert(1)\">[click](javascript:alert(1))";
        Topic topic = persistTopic(author.getId(), "Hostile Topic", hostile, TopicApprovalStatus.APPROVED);

        String body = detailBody(author, topic.getId());

        assertThat(body).doesNotContain("<script").doesNotContain("onerror").doesNotContain("javascript:");
        assertThat(body).contains("Hello");
    }

    @Test
    void aPlainProseDescriptionWrittenBeforeThisFeatureStillReadsTheSame() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        Topic topic = persistTopic(
                author.getId(),
                "Legacy Topic",
                "We want to build something useful for the community.",
                TopicApprovalStatus.APPROVED);

        String body = detailBody(author, topic.getId());

        assertThat(body).contains("<p>We want to build something useful for the community.</p>");
    }

    @Test
    void everyLinkInADescriptionOpensInANewTabWithoutWindowOpenerAccess() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        Topic topic = persistTopic(
                author.getId(), "Link Topic", "[docs](https://example.com)", TopicApprovalStatus.APPROVED);

        String body = detailBody(author, topic.getId());

        assertThat(body).contains("target=\"_blank\"");
        assertThat(body).contains("noopener");
    }

    // --- Feature 010 User Story 2: markdown authoring hint (T014) ------------------------------

    @Test
    void theProposeFormLabelsTheDescriptionAsMarkdownAndDescribesTheBasics() {
        User author = persistUser(false);
        persistParticipant(author.getId());

        String body = bodyOf(author, "/topics/new");

        assertThat(body).contains("Description (Markdown)");
        assertThat(body).contains("id=\"description-hint\"");
        assertThat(body).contains("aria-describedby=\"description-hint\"");
        assertThat(body).contains("becomes a link");
    }

    @Test
    void theEditFormCarriesTheSameHintAndEchoesTheAuthorsRawMarkdown() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        String markdown = "# Heading\n\n- a list item";
        Topic topic = persistTopic(author.getId(), "Hint Topic", markdown, TopicApprovalStatus.APPROVED);

        String body = bodyOf(author, "/topics/" + topic.getId() + "/edit");

        assertThat(body).contains("Description (Markdown)");
        assertThat(body).contains("aria-describedby=\"description-hint\"");
        // Story 2 Scenario 4: the textarea holds the markdown as written, never rendered HTML.
        assertThat(body).contains("# Heading").contains("- a list item");
    }

    // --- Feature 010 User Story 3: attachments (T020, T021) ------------------------------------

    @Test
    void theAuthorCanUploadAnAttachmentAndSeeItListedOnBothScreens() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        Topic topic = persistTopic(author.getId(), "Attach Topic", "Description", TopicApprovalStatus.APPROVED);

        uploadAttachment(author, topic.getId(), "brief.pdf", "application/pdf", "PDF-BYTES".getBytes())
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER)
                .expectHeader()
                .value("Location", location -> assertThat(location).endsWith("/edit?attachment=added"));

        String editBody = bodyOf(author, "/topics/" + topic.getId() + "/edit?attachment=added");
        assertThat(editBody).contains("brief.pdf");
        assertThat(editBody).contains("role=\"status\"");
        assertThat(editBody).contains("Save any text changes above before uploading");

        String detailBody = detailBody(author, topic.getId());
        assertThat(detailBody).contains("brief.pdf");
        assertThat(detailBody).contains("/attachments/");
    }

    @Test
    void theAuthorCanRemoveAnAttachmentWithoutAConfirmationStep() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        Topic topic = persistTopic(author.getId(), "Remove Topic", "Description", TopicApprovalStatus.APPROVED);
        uploadAttachment(author, topic.getId(), "obsolete.pdf", "application/pdf", "BYTES".getBytes())
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
        UUID attachmentId = onlyAttachmentId(topic.getId());

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics/{tid}/attachments/{aid}/delete", topic.getId(), attachmentId)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER)
                .expectHeader()
                .value("Location", location -> assertThat(location).endsWith("/edit?attachment=removed"));

        assertThat(detailBody(author, topic.getId())).doesNotContain("obsolete.pdf");
        webTestClient
                .mutateWith(loginAs(author))
                .get()
                .uri("/topics/{tid}/attachments/{aid}", topic.getId(), attachmentId)
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void anUploadOfADisallowedTypeOrAnOversizeFileIsRejectedWithoutStoringAnything() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        Topic topic = persistTopic(
                author.getId(), "Reject Topic", "The original description", TopicApprovalStatus.APPROVED);

        String body = uploadAttachment(author, topic.getId(), "payload.exe", "application/pdf", "X".getBytes())
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // F2: the rejected form must come back fully populated, not blank.
        assertThat(body).contains("Only PDF, Word, Excel");
        assertThat(body).contains("The original description");
        assertThat(body).contains("Reject Topic");
        assertThat(attachmentCount(topic.getId())).isZero();

        byte[] tooLarge = new byte[10 * 1024 * 1024 + 1];
        String sizeBody = uploadAttachment(author, topic.getId(), "big.pdf", "application/pdf", tooLarge)
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(sizeBody).contains("10 MB or smaller");
        assertThat(attachmentCount(topic.getId())).isZero();
    }

    @Test
    void attachmentChangesNeverAlterTheTopicsApprovalStatus() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        Topic topic = persistTopic(author.getId(), "Pending Topic", "Description", TopicApprovalStatus.PENDING);

        uploadAttachment(author, topic.getId(), "brief.pdf", "application/pdf", "BYTES".getBytes())
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(topicRepository.findById(topic.getId()).block().getApprovalStatus())
                .isEqualTo(TopicApprovalStatus.PENDING);
    }

    @Test
    void twoAttachmentsMayShareTheSameFileNameWithoutEitherBeingLost() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        Topic topic = persistTopic(author.getId(), "Duplicate Topic", "Description", TopicApprovalStatus.APPROVED);

        uploadAttachment(author, topic.getId(), "notes.txt", "text/plain", "first".getBytes())
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
        uploadAttachment(author, topic.getId(), "notes.txt", "text/plain", "second".getBytes())
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(attachmentCount(topic.getId())).isEqualTo(2);
    }

    @Test
    void aNonAuthorSeesNoAttachmentControlsAndIsRefusedOnADirectRequest() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        User stranger = persistUser(false);
        persistParticipant(stranger.getId());
        Topic topic = persistTopic(author.getId(), "Guarded Topic", "Description", TopicApprovalStatus.APPROVED);
        uploadAttachment(author, topic.getId(), "brief.pdf", "application/pdf", "BYTES".getBytes())
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
        UUID attachmentId = onlyAttachmentId(topic.getId());

        String detailBody = detailBody(stranger, topic.getId());
        assertThat(detailBody).contains("brief.pdf");
        assertThat(detailBody).doesNotContain("enctype=\"multipart/form-data\"");
        assertThat(detailBody).doesNotContain(">Remove<");

        uploadAttachment(stranger, topic.getId(), "hijack.pdf", "application/pdf", "X".getBytes())
                .expectStatus()
                .isForbidden();

        webTestClient
                .mutateWith(loginAs(stranger))
                .post()
                .uri("/topics/{tid}/attachments/{aid}/delete", topic.getId(), attachmentId)
                .exchange()
                .expectStatus()
                .isForbidden();

        assertThat(attachmentCount(topic.getId())).isEqualTo(1);
    }

    @Test
    void theProposeFormOffersNoUploadAndSaysAttachmentsComeLater() {
        User author = persistUser(false);
        persistParticipant(author.getId());

        String body = bodyOf(author, "/topics/new");

        assertThat(body).contains("Attachments can be added after the Topic is saved.");
        assertThat(body).doesNotContain("type=\"file\"");
    }

    @Test
    void downloadingAnAttachmentDeliversTheBytesAsAFileUnderItsOriginalName() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        Topic topic = persistTopic(author.getId(), "Download Topic", "Description", TopicApprovalStatus.APPROVED);
        byte[] contents = "the original bytes".getBytes();
        uploadAttachment(author, topic.getId(), "Rapport été.txt", "text/plain", contents)
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
        UUID attachmentId = onlyAttachmentId(topic.getId());

        byte[] downloaded = webTestClient
                .mutateWith(loginAs(author))
                .get()
                .uri("/topics/{tid}/attachments/{aid}", topic.getId(), attachmentId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .value("Content-Disposition", disposition -> {
                    assertThat(disposition).startsWith("attachment");
                    // A non-ASCII name survives via the RFC 5987 filename* form.
                    assertThat(disposition).contains("filename*=UTF-8''");
                })
                .expectHeader()
                .value("Cache-Control", cacheControl -> assertThat(cacheControl).contains("no-store"))
                .expectBody()
                .returnResult()
                .getResponseBodyContent();

        assertThat(downloaded).isEqualTo(contents);
    }

    @Test
    void anAttachmentIsNotDownloadableByAUserWhoCannotSeeItsTopic() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        User stranger = persistUser(false);
        persistParticipant(stranger.getId());
        Topic pending = persistTopic(author.getId(), "Hidden Topic", "Description", TopicApprovalStatus.PENDING);
        uploadAttachment(author, pending.getId(), "secret.pdf", "application/pdf", "BYTES".getBytes())
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
        UUID attachmentId = onlyAttachmentId(pending.getId());

        webTestClient
                .mutateWith(loginAs(stranger))
                .get()
                .uri("/topics/{tid}/attachments/{aid}", pending.getId(), attachmentId)
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void anAttachmentCannotBeFetchedThroughADifferentTopicsUrl() {
        User author = persistUser(false);
        persistParticipant(author.getId());
        Topic owning = persistTopic(author.getId(), "Owning Topic", "Description", TopicApprovalStatus.APPROVED);
        Topic other = persistTopic(author.getId(), "Other Topic", "Description", TopicApprovalStatus.APPROVED);
        uploadAttachment(author, owning.getId(), "brief.pdf", "application/pdf", "BYTES".getBytes())
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
        UUID attachmentId = onlyAttachmentId(owning.getId());

        webTestClient
                .mutateWith(loginAs(author))
                .get()
                .uri("/topics/{tid}/attachments/{aid}", other.getId(), attachmentId)
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    private WebTestClient.ResponseSpec uploadAttachment(
            User user, UUID topicId, String fileName, String contentType, byte[] bytes) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(bytes) {
                    @Override
                    public String getFilename() {
                        return fileName;
                    }
                })
                .contentType(MediaType.parseMediaType(contentType));
        return webTestClient
                .mutateWith(loginAs(user))
                .post()
                .uri("/topics/{id}/attachments", topicId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange();
    }

    private UUID onlyAttachmentId(UUID topicId) {
        return databaseClient
                .sql("SELECT id FROM topic_attachments WHERE topic_id = :tid ORDER BY created_at, id")
                .bind("tid", topicId)
                .mapValue(UUID.class)
                .first()
                .block();
    }

    private long attachmentCount(UUID topicId) {
        return databaseClient
                .sql("SELECT count(*) FROM topic_attachments WHERE topic_id = :tid")
                .bind("tid", topicId)
                .mapValue(Long.class)
                .one()
                .block();
    }

    private String detailBody(User viewer, UUID topicId) {
        return bodyOf(viewer, "/topics/" + topicId);
    }

    private String bodyOf(User viewer, String uri) {
        return new String(webTestClient
                .mutateWith(loginAs(viewer))
                .get()
                .uri(uri)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBodyContent());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
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

    private Participant persistParticipant(UUID userId) {
        Participant participant = new Participant();
        participant.setUserId(userId);
        participant.setStatus(ParticipantStatus.ACTIVE);
        Instant now = Instant.now();
        participant.setCreatedAt(now);
        participant.setUpdatedAt(now);
        return participantRepository.save(participant).block();
    }

    private Skill persistSkill(String name) {
        Skill skill = new Skill();
        skill.setName(name);
        Instant now = Instant.now();
        skill.setCreatedAt(now);
        skill.setUpdatedAt(now);
        return skillRepository.save(skill).block();
    }

    private Topic persistTopic(UUID creatorUserId, String name, String description, TopicApprovalStatus status) {
        Topic topic = new Topic();
        topic.setName(name);
        topic.setDescription(description);
        topic.setCreatedByUserId(creatorUserId);
        topic.setApprovalStatus(status);
        Instant now = Instant.now();
        topic.setCreatedAt(now);
        topic.setUpdatedAt(now);
        return topicRepository.save(topic).block();
    }

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

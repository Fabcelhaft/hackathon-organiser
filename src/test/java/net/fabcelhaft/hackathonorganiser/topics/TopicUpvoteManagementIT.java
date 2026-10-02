package net.fabcelhaft.hackathonorganiser.topics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsRepository;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import net.fabcelhaft.hackathonorganiser.topic.Topic;
import net.fabcelhaft.hackathonorganiser.topic.TopicApprovalStatus;
import net.fabcelhaft.hackathonorganiser.topic.TopicRepository;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
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
 * Integration tests for User Story 1's upvote/withdraw actions (contracts/topic-upvote-
 * action.md): one active upvote per (Topic, user), idempotent re-casting, self-upvoting allowed,
 * 404 for an unknown/invisible Topic or while the feature is disabled, the admin toggle hiding and
 * later restoring both the count and each user's own state, and — the anonymity guarantee (FR-003)
 * — no trace of an upvote/withdraw ever reaching the Organiser Audit Trail.
 */
@SpringBootTest
@Testcontainers
class TopicUpvoteManagementIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @Autowired
    ApplicationContext applicationContext;

    WebTestClient webTestClient;

    @Autowired
    UserRepository userRepository;

    @Autowired
    TopicRepository topicRepository;

    @Autowired
    OrganiserSettingsRepository organiserSettingsRepository;

    @Autowired
    DatabaseClient databaseClient;

    @BeforeEach
    void setUpWebTestClient() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    @BeforeEach
    void resetTopicsBetweenTests() {
        databaseClient.sql("DELETE FROM topic_upvotes").then().block();
        databaseClient.sql("DELETE FROM topic_skills").then().block();
        databaseClient.sql("DELETE FROM topics").then().block();
    }

    @BeforeEach
    void resetUpvotingToggleToEnabled() {
        setUpvotingEnabled(true);
    }

    // --- Feature 013: the fragment branch (contracts/vote-control-fragment.md) -------------------

    // FR-006a: with the header, the response is the re-rendered control carrying the four values
    // topic-vote.js reconciles against — not a redirect.
    @Test
    void theFragmentHeaderReturnsTheRerenderedVoteControlInsteadOfARedirect() {
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        User voter = persistUser(false);

        String body = webTestClient
                .mutateWith(loginAs(voter))
                .post()
                .uri("/topics/{id}/upvote", topic.getId())
                .header("X-Vote-Fragment", "true")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("data-vote-form");
        assertThat(body).contains("data-vote-count");
        assertThat(body).contains("data-vote-button");
        assertThat(body)
                .withFailMessage("the fragment must carry the post-action state, not the pre-action one")
                .contains("aria-pressed=\"true\"")
                .contains("aria-label=\"Withdraw upvote for " + topic.getName() + "\"")
                .contains("/topics/" + topic.getId() + "/unupvote");
        assertThat(upvoteCount(topic.getId())).isEqualTo(1);
    }

    @Test
    void theFragmentBranchReflectsTheWithdrawnStateAfterUnupvoting() {
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        User voter = persistUser(false);
        webTestClient.mutateWith(loginAs(voter)).post().uri("/topics/{id}/upvote", topic.getId()).exchange();

        String body = webTestClient
                .mutateWith(loginAs(voter))
                .post()
                .uri("/topics/{id}/unupvote", topic.getId())
                .header("X-Vote-Fragment", "true")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("aria-pressed=\"false\"");
        assertThat(body).contains("aria-label=\"Upvote " + topic.getName() + "\"");
        assertThat(body).contains("/topics/" + topic.getId() + "/upvote");
        assertThat(upvoteCount(topic.getId())).isZero();
    }

    // FR-006b: the fallback is the whole safety net for users without scripting, so the absence of
    // the header must still produce exactly the 303 that shipped before feature 013.
    @Test
    void withoutTheFragmentHeaderTheRouteStillRedirectsExactlyAsBefore() {
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        User voter = persistUser(false);

        webTestClient
                .mutateWith(loginAs(voter))
                .post()
                .uri("/topics/{id}/upvote", topic.getId())
                .body(BodyInserters.fromFormData("redirect", "/topics/overview"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER)
                .expectHeader()
                .valueEquals("Location", "/topics/overview");
    }

    // The allow-list still governs the value echoed back into the re-rendered form, so a tampered
    // redirect cannot ride along into the fragment and become the next submission's target.
    @Test
    void theFragmentBranchNeverEchoesBackATamperedRedirectTarget() {
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        User voter = persistUser(false);

        String body = webTestClient
                .mutateWith(loginAs(voter))
                .post()
                .uri("/topics/{id}/upvote", topic.getId())
                .header("X-Vote-Fragment", "true")
                .body(BodyInserters.fromFormData("redirect", "https://example.com/evil"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).doesNotContain("example.com");
        assertThat(body).contains("name=\"redirect\" value=\"/\"");
    }

    // FR-009 / the gate: the fragment branch must not become a way around the feature toggle or
    // the Topic-visibility rule.
    @Test
    void theFragmentBranchIsGatedExactlyLikeTheRedirectBranch() {
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        User voter = persistUser(false);
        setUpvotingEnabled(false);

        webTestClient
                .mutateWith(loginAs(voter))
                .post()
                .uri("/topics/{id}/upvote", topic.getId())
                .header("X-Vote-Fragment", "true")
                .exchange()
                .expectStatus()
                .isNotFound();

        setUpvotingEnabled(true);

        webTestClient
                .mutateWith(loginAs(voter))
                .post()
                .uri("/topics/{id}/upvote", UUID.randomUUID())
                .header("X-Vote-Fragment", "true")
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void upvotingIncreasesTheCountAndWithdrawingDecreasesItAgain() {
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        User voter = persistUser(false);

        webTestClient
                .mutateWith(loginAs(voter))
                .post()
                .uri("/topics/{id}/upvote", topic.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(upvoteCount(topic.getId())).isEqualTo(1);
        assertThat(detailBody(voter, topic.getId())).contains("Withdraw upvote");

        webTestClient
                .mutateWith(loginAs(voter))
                .post()
                .uri("/topics/{id}/unupvote", topic.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(upvoteCount(topic.getId())).isEqualTo(0);
        assertThat(detailBody(voter, topic.getId())).doesNotContain("Withdraw upvote");
    }

    @Test
    void aSecondUpvoteByTheSameUserLeavesTheCountUnchanged() {
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        User voter = persistUser(false);

        webTestClient.mutateWith(loginAs(voter)).post().uri("/topics/{id}/upvote", topic.getId()).exchange();
        webTestClient.mutateWith(loginAs(voter)).post().uri("/topics/{id}/upvote", topic.getId()).exchange();

        assertThat(upvoteCount(topic.getId())).isEqualTo(1);
    }

    @Test
    void withdrawingWithNoActiveUpvoteIsANoOp() {
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        User voter = persistUser(false);

        webTestClient
                .mutateWith(loginAs(voter))
                .post()
                .uri("/topics/{id}/unupvote", topic.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(upvoteCount(topic.getId())).isEqualTo(0);
    }

    @Test
    void multipleUsersUpvotingAccumulateAndNoUpvoterIdentityIsEverExposed() {
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        User voterA = persistUser(false);
        User voterB = persistUser(false);

        webTestClient.mutateWith(loginAs(voterA)).post().uri("/topics/{id}/upvote", topic.getId()).exchange();
        webTestClient.mutateWith(loginAs(voterB)).post().uri("/topics/{id}/upvote", topic.getId()).exchange();

        assertThat(upvoteCount(topic.getId())).isEqualTo(2);
        User organiser = persistUser(true);
        String overviewBody = overviewBody(organiser);
        assertThat(overviewBody).doesNotContain(voterA.getDisplayName());
        assertThat(overviewBody).doesNotContain(voterB.getDisplayName());
    }

    @Test
    void aTopicsOwnAuthorCanUpvoteTheirOwnTopic() {
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);

        webTestClient
                .mutateWith(loginAs(author))
                .post()
                .uri("/topics/{id}/upvote", topic.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(upvoteCount(topic.getId())).isEqualTo(1);
    }

    @Test
    void bothRoutesReturn404ForAnUnknownOrInvisiblePendingTopic() {
        User author = persistUser(false);
        Topic pending = persistTopic(author.getId(), TopicApprovalStatus.PENDING);
        User otherViewer = persistUser(false);

        webTestClient
                .mutateWith(loginAs(otherViewer))
                .post()
                .uri("/topics/{id}/upvote", UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isNotFound();
        webTestClient
                .mutateWith(loginAs(otherViewer))
                .post()
                .uri("/topics/{id}/upvote", pending.getId())
                .exchange()
                .expectStatus()
                .isNotFound();
        webTestClient
                .mutateWith(loginAs(otherViewer))
                .post()
                .uri("/topics/{id}/unupvote", pending.getId())
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void aValidatedRedirectTargetIsHonoredButAnUnrecognisedOneFallsBackHome() {
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        User voter = persistUser(false);

        webTestClient
                .mutateWith(loginAs(voter))
                .post()
                .uri("/topics/{id}/upvote", topic.getId())
                .body(BodyInserters.fromFormData("redirect", "/topics/overview"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER)
                .expectHeader()
                .valueEquals("Location", "/topics/overview");

        webTestClient
                .mutateWith(loginAs(voter))
                .post()
                .uri("/topics/{id}/unupvote", topic.getId())
                .body(BodyInserters.fromFormData("redirect", "https://evil.example.com/"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER)
                .expectHeader()
                .valueEquals("Location", "/");
    }

    // --- Admin toggle: hides everything, preserves data, restores on re-enable (FR-006-FR-008) ---

    @Test
    void disablingHidesTheControlAndCountEverywhereThenReEnablingRestoresThemUnchanged() {
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        User voterA = persistUser(false);
        User voterB = persistUser(false);
        webTestClient.mutateWith(loginAs(voterA)).post().uri("/topics/{id}/upvote", topic.getId()).exchange();
        webTestClient.mutateWith(loginAs(voterB)).post().uri("/topics/{id}/upvote", topic.getId()).exchange();
        assertThat(upvoteCount(topic.getId())).isEqualTo(2);

        setUpvotingEnabled(false);

        assertThat(homeBody(voterA)).doesNotContain("Upvote");
        assertThat(overviewBody(voterA)).doesNotContain("Upvote");
        assertThat(detailBody(voterA, topic.getId())).doesNotContain("Upvote");
        webTestClient
                .mutateWith(loginAs(voterA))
                .post()
                .uri("/topics/{id}/upvote", topic.getId())
                .exchange()
                .expectStatus()
                .isNotFound();
        webTestClient
                .mutateWith(loginAs(voterA))
                .post()
                .uri("/topics/{id}/unupvote", topic.getId())
                .exchange()
                .expectStatus()
                .isNotFound();
        // The data itself survives being hidden — never deleted by disabling the feature.
        assertThat(upvoteCount(topic.getId())).isEqualTo(2);

        setUpvotingEnabled(true);

        assertThat(upvoteCount(topic.getId())).isEqualTo(2);
        assertThat(detailBody(voterA, topic.getId())).contains("Withdraw upvote");
        assertThat(detailBody(persistUser(false), topic.getId())).contains("Upvote");
    }

    // --- Anonymity: never in the Audit Trail, even for an Organiser (FR-003, SC-003) --------------

    @Test
    void upvoteAndWithdrawNeverAppearInTheOrganiserAuditTrail() {
        User author = persistUser(false);
        Topic topic = persistTopic(author.getId(), TopicApprovalStatus.APPROVED);
        User voter = persistUser(false);
        User organiser = persistUser(true);

        String auditBeforeAnyChange = auditBody(organiser, topic.getId());

        webTestClient.mutateWith(loginAs(voter)).post().uri("/topics/{id}/upvote", topic.getId()).exchange();
        webTestClient.mutateWith(loginAs(voter)).post().uri("/topics/{id}/unupvote", topic.getId()).exchange();
        webTestClient.mutateWith(loginAs(voter)).post().uri("/topics/{id}/upvote", topic.getId()).exchange();

        String auditAfterUpvoteActions = auditBody(organiser, topic.getId());

        assertThat(auditAfterUpvoteActions).isEqualTo(auditBeforeAnyChange);
        assertThat(auditAfterUpvoteActions).doesNotContain(voter.getDisplayName());
    }

    // --- Test helpers ------------------------------------------------------------------------------

    private Integer upvoteCount(UUID topicId) {
        return databaseClient
                .sql("SELECT COUNT(*) FROM topic_upvotes WHERE topic_id = :tid")
                .bind("tid", topicId)
                .mapValue(Long.class)
                .one()
                .map(Long::intValue)
                .block();
    }

    private void setUpvotingEnabled(boolean enabled) {
        organiserSettingsRepository
                .findBySingletonTrue()
                .flatMap(settings -> {
                    settings.setTopicUpvotingEnabled(enabled);
                    settings.setUpdatedAt(Instant.now());
                    return organiserSettingsRepository.save(settings);
                })
                .block();
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

    private String overviewBody(User user) {
        return webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/topics/overview")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private String detailBody(User user, UUID topicId) {
        return webTestClient
                .mutateWith(loginAs(user))
                .get()
                .uri("/topics/{id}", topicId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private String auditBody(User organiser, UUID topicId) {
        return webTestClient
                .mutateWith(loginAs(organiser))
                .get()
                .uri("/organiser/topics/{id}/audit", topicId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
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

    private Topic persistTopic(UUID creatorUserId, TopicApprovalStatus status) {
        Topic topic = new Topic();
        topic.setName("Topic " + UUID.randomUUID());
        topic.setDescription("Description " + UUID.randomUUID());
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

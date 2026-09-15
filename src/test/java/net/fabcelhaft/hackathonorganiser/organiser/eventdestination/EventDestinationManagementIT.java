package net.fabcelhaft.hackathonorganiser.organiser.eventdestination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.event.EventDestination;
import net.fabcelhaft.hackathonorganiser.event.EventDestinationRepository;
import net.fabcelhaft.hackathonorganiser.event.EventDestinationType;
import net.fabcelhaft.hackathonorganiser.event.EventType;
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

/**
 * Integration tests for {@link EventDestinationController} (spec.md Stories 1, 2 and 4;
 * FR-001-FR-020c) against the real {@code SecurityWebFilterChain} and repositories, following the
 * same {@code WebTestClient} + {@code mockOidcLogin()} + Testcontainers pattern as
 * {@code GroupManagementIT}.
 */
@SpringBootTest
@Testcontainers
class EventDestinationManagementIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @Autowired
    ApplicationContext applicationContext;

    WebTestClient webTestClient;

    @BeforeEach
    void setUpWebTestClient() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    @Autowired
    UserRepository userRepository;

    @Autowired
    EventDestinationRepository eventDestinationRepository;

    @Test
    void createWithValidKafkaFieldsRedirectsAndTheRowAppearsInTheList() {
        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/event-destinations")
                .body(BodyInserters.fromFormData("name", "Kafka Dest " + UUID.randomUUID())
                        .with("type", "KAFKA")
                        .with("kafka_bootstrap_servers", "localhost:9092")
                        .with("kafka_topic", "events")
                        .with("event_types", "PARTICIPANT_REGISTERED"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        String body = listBody();
        assertThat(body).contains("Kafka Dest");
        assertThat(body).contains("Disabled");
    }

    @Test
    void createWithAMissingRequiredFieldReRendersTheFormWithAnError() {
        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/event-destinations")
                .body(BodyInserters.fromFormData("name", "No URL")
                        .with("type", "HTTP_POST"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("A URL is required"));

        assertThat(eventDestinationRepository.findByName("No URL").blockOptional()).isEmpty();
    }

    @Test
    void createWithADuplicateNameReRendersTheFormWithAnError() {
        String name = "Dup " + UUID.randomUUID();
        persistDestination(name, false, Instant.now());

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/event-destinations")
                .body(BodyInserters.fromFormData("name", name)
                        .with("type", "HTTP_POST")
                        .with("http_url", "https://example.com"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("already exists"));
    }

    @Test
    void aStandardUserIsDeniedEveryRoute() {
        webTestClient.mutateWith(standardUser())
                .get()
                .uri("/organiser/event-destinations")
                .exchange()
                .expectStatus()
                .isForbidden();

        webTestClient.mutateWith(standardUser())
                .get()
                .uri("/organiser/event-destinations/new")
                .exchange()
                .expectStatus()
                .isForbidden();

        webTestClient.mutateWith(standardUser())
                .post()
                .uri("/organiser/event-destinations")
                .body(BodyInserters.fromFormData("name", "x"))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void selectedEventTypesArePersistedAndShownOnTheListAndPreCheckedOnTheEditForm() {
        String name = "Selective " + UUID.randomUUID();
        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/event-destinations")
                .body(BodyInserters.fromFormData("name", name)
                        .with("type", "HTTP_POST")
                        .with("http_url", "https://example.com")
                        .with("event_types", "PARTICIPANT_REGISTERED")
                        .with("event_types", "TOPIC_APPROVED"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        String listHtml = listBody();
        assertThat(listHtml).contains("Participant registered");
        assertThat(listHtml).contains("Topic approved");
        assertThat(listHtml).doesNotContain("Organiser role added");

        EventDestination saved = eventDestinationRepository.findByName(name).block();
        String editHtml = webTestClient
                .mutateWith(organiser())
                .get()
                .uri("/organiser/event-destinations/{id}/edit", saved.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(editHtml).contains("checked");
    }

    @Test
    void editUpdatesTheUrlAndAConcurrentStaleEditIsRejected() {
        EventDestination existing = persistDestination("Editable " + UUID.randomUUID(), true, Instant.now());

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/event-destinations/{id}", existing.getId())
                .body(BodyInserters.fromFormData("name", existing.getName())
                        .with("type", "HTTP_POST")
                        .with("http_url", "https://new-url.example.com")
                        .with("updated_at", existing.getUpdatedAt().toString()))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        EventDestination afterFirstEdit = eventDestinationRepository.findById(existing.getId()).block();
        assertThat(afterFirstEdit.getHttpUrl()).isEqualTo("https://new-url.example.com");

        // A second edit submitted with the now-stale original updated_at must be rejected, not
        // silently overwrite the first edit (FR-018).
        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/event-destinations/{id}", existing.getId())
                .body(BodyInserters.fromFormData("name", existing.getName())
                        .with("type", "HTTP_POST")
                        .with("http_url", "https://should-not-apply.example.com")
                        .with("updated_at", existing.getUpdatedAt().toString()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("changed by someone else"));

        EventDestination stillFirstEdit = eventDestinationRepository.findById(existing.getId()).block();
        assertThat(stillFirstEdit.getHttpUrl()).isEqualTo("https://new-url.example.com");
    }

    @Test
    void enableAndDisableToggleOnlyTheEnabledFlag() {
        EventDestination existing = persistDestination("Toggle " + UUID.randomUUID(), false, Instant.now());

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/event-destinations/{id}/enable", existing.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(eventDestinationRepository.findById(existing.getId()).block().isEnabled()).isTrue();

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/event-destinations/{id}/disable", existing.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(eventDestinationRepository.findById(existing.getId()).block().isEnabled()).isFalse();
    }

    @Test
    void deleteRemovesTheDestinationFromTheList() {
        EventDestination existing = persistDestination("Deletable " + UUID.randomUUID(), false, Instant.now());

        webTestClient
                .mutateWith(organiser())
                .post()
                .uri("/organiser/event-destinations/{id}/delete", existing.getId())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER);

        assertThat(eventDestinationRepository.findById(existing.getId()).blockOptional()).isEmpty();
    }

    @Test
    void theStoredCredentialIsNeverRedisplayedInTheEditFormHtml() {
        EventDestination existing = persistDestination("Secretive " + UUID.randomUUID(), true, Instant.now());
        existing.setCredential("super-secret-value");
        eventDestinationRepository.save(existing).block();

        String editHtml = webTestClient
                .mutateWith(organiser())
                .get()
                .uri("/organiser/event-destinations/{id}/edit", existing.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(editHtml).doesNotContain("super-secret-value");
    }

    private String listBody() {
        return webTestClient
                .mutateWith(organiser())
                .get()
                .uri("/organiser/event-destinations")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private EventDestination persistDestination(String name, boolean http, Instant now) {
        EventDestination destination = new EventDestination();
        destination.setName(name);
        if (http) {
            destination.setType(EventDestinationType.HTTP_POST);
            destination.setHttpUrl("https://example.com/" + name);
        } else {
            destination.setType(EventDestinationType.KAFKA);
            destination.setKafkaBootstrapServers("localhost:9092");
            destination.setKafkaTopic("events");
        }
        destination.setEnabled(false);
        destination.setCreatedAt(now);
        destination.setUpdatedAt(now);
        return eventDestinationRepository.save(destination).block();
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

    private OidcLoginMutator organiser() {
        return loginAsUser(persistUser("Organiser " + UUID.randomUUID(), true));
    }

    private OidcLoginMutator standardUser() {
        return loginAsUser(persistUser("Standard User " + UUID.randomUUID(), false));
    }

    private static OidcLoginMutator loginAsUser(User user) {
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

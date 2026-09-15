package net.fabcelhaft.hackathonorganiser.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceStatus;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldAnswer;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinition;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldOption;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldService;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldType;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupStatus;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
import net.fabcelhaft.hackathonorganiser.topic.Topic;
import net.fabcelhaft.hackathonorganiser.topic.TopicApprovalStatus;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link EventPayloadFactory} (data-model.md "Event payload shapes" and "Event
 * Type -> payload composition"; contracts/event-payloads.md). Asserts the built payload's keys and
 * nested field names exactly match the documented shape for each of the 13 Event Types.
 */
@ExtendWith(MockitoExtension.class)
class EventPayloadFactoryTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CustomFieldService customFieldService;

    private EventPayloadFactory factory;

    @BeforeEach
    void setUp() {
        factory = new EventPayloadFactory(userRepository, customFieldService);
    }

    @Test
    void topicPayloadHasEveryDocumentedField() {
        Topic topic = topicOf();

        var payload = factory.topicPayload(topic);

        assertThat(payload)
                .containsOnlyKeys(
                        "id", "name", "description", "createdByUserId", "approvalStatus", "createdAt", "updatedAt");
        assertThat(payload.get("name")).isEqualTo("Realtime dashboard");
    }

    @Test
    void participantPayloadHasEveryDocumentedField() {
        Participant participant = participantOf();

        var payload = factory.participantPayload(participant);

        assertThat(payload).containsOnlyKeys("id", "userId", "status", "createdAt", "updatedAt");
        assertThat(payload.get("status")).isEqualTo(ParticipantStatus.ACTIVE);
    }

    @Test
    void userPayloadExcludesTheInternalOidcSubject() {
        User user = userOf();

        var payload = factory.userPayload(user);

        assertThat(payload).containsOnlyKeys("id", "displayName", "email", "organiser", "createdAt", "updatedAt");
        assertThat(payload).doesNotContainKey("oidcSubject");
    }

    @Test
    void groupPayloadCarriesTheGivenComplianceStatus() {
        Group group = groupOf();

        var payload = factory.groupPayload(group, ComplianceStatus.NOT_COMPLIANT);

        assertThat(payload)
                .containsOnlyKeys(
                        "id", "topicId", "status", "complianceOverride", "complianceStatus", "createdAt", "updatedAt");
        assertThat(payload.get("complianceStatus")).isEqualTo(ComplianceStatus.NOT_COMPLIANT);
    }

    @Test
    void groupPayloadAllowsANullComplianceStatusForADisbandedGroup() {
        Group group = groupOf();

        var payload = factory.groupPayload(group, null);

        assertThat(payload.get("complianceStatus")).isNull();
    }

    @Test
    void participantRegisteredCarriesTheParticipantTheUserAndCustomFields() {
        Participant participant = participantOf();
        User user = userOf();
        stubUserAndCustomFields(participant, user, List.of(answerOf("M")));

        StepVerifier.create(factory.participantRegistered(participant))
                .assertNext(event -> {
                    assertThat(event.eventType()).isEqualTo(EventType.PARTICIPANT_REGISTERED);
                    assertThat(event.payload()).containsOnlyKeys("participant", "user", "customFields");
                    assertUserPayloadMatches(event.payload(), user);
                    assertCustomFieldsPayloadHasOneAnswerOf(event.payload(), "M");
                })
                .verifyComplete();
    }

    @Test
    void participantRevokedCarriesTheParticipantTheUserAndCustomFields() {
        Participant participant = participantOf();
        User user = userOf();
        stubUserAndCustomFields(participant, user, List.of());

        StepVerifier.create(factory.participantRevoked(participant))
                .assertNext(event -> {
                    assertThat(event.eventType()).isEqualTo(EventType.PARTICIPANT_REVOKED);
                    assertThat(event.payload()).containsOnlyKeys("participant", "user", "customFields");
                    assertUserPayloadMatches(event.payload(), user);
                })
                .verifyComplete();
    }

    @Test
    void participantNotParticipatedCarriesTheParticipantTheUserAndCustomFields() {
        Participant participant = participantOf();
        User user = userOf();
        stubUserAndCustomFields(participant, user, List.of());

        StepVerifier.create(factory.participantNotParticipated(participant))
                .assertNext(event -> {
                    assertThat(event.eventType()).isEqualTo(EventType.PARTICIPANT_NOT_PARTICIPATED);
                    assertThat(event.payload()).containsOnlyKeys("participant", "user", "customFields");
                    assertUserPayloadMatches(event.payload(), user);
                })
                .verifyComplete();
    }

    @Test
    void participantRegisteredReturnsABlankAnswerForAnUnansweredCustomField() {
        Participant participant = participantOf();
        User user = userOf();
        stubUserAndCustomFields(participant, user, List.of(answerOf("")));

        StepVerifier.create(factory.participantRegistered(participant))
                .assertNext(event -> assertCustomFieldsPayloadHasOneAnswerOf(event.payload(), ""))
                .verifyComplete();
    }

    @Test
    void userCreatedCarriesOnlyTheUser() {
        DomainEvent event = factory.userCreated(userOf());

        assertThat(event.eventType()).isEqualTo(EventType.USER_CREATED);
        assertThat(event.payload()).containsOnlyKeys("user");
    }

    @Test
    void topicProposedCarriesOnlyTheTopic() {
        DomainEvent event = factory.topicProposed(topicOf());

        assertThat(event.eventType()).isEqualTo(EventType.TOPIC_PROPOSED);
        assertThat(event.payload()).containsOnlyKeys("topic");
    }

    @Test
    void topicApprovedCarriesOnlyTheTopic() {
        DomainEvent event = factory.topicApproved(topicOf());

        assertThat(event.eventType()).isEqualTo(EventType.TOPIC_APPROVED);
        assertThat(event.payload()).containsOnlyKeys("topic");
    }

    @Test
    void participantJoinedTopicCarriesTopicParticipantUserAndCustomFields() {
        Participant participant = participantOf();
        User user = userOf();
        stubUserAndCustomFields(participant, user, List.of());

        StepVerifier.create(factory.participantJoinedTopic(topicOf(), participant))
                .assertNext(event -> {
                    assertThat(event.eventType()).isEqualTo(EventType.PARTICIPANT_JOINED_TOPIC);
                    assertThat(event.payload()).containsOnlyKeys("topic", "participant", "user", "customFields");
                    assertUserPayloadMatches(event.payload(), user);
                })
                .verifyComplete();
    }

    @Test
    void participantLeftTopicCarriesTopicParticipantUserAndCustomFields() {
        Participant participant = participantOf();
        User user = userOf();
        stubUserAndCustomFields(participant, user, List.of());

        StepVerifier.create(factory.participantLeftTopic(topicOf(), participant))
                .assertNext(event -> {
                    assertThat(event.eventType()).isEqualTo(EventType.PARTICIPANT_LEFT_TOPIC);
                    assertThat(event.payload()).containsOnlyKeys("topic", "participant", "user", "customFields");
                    assertUserPayloadMatches(event.payload(), user);
                })
                .verifyComplete();
    }

    @Test
    void organiserRoleAddedCarriesOnlyTheUser() {
        DomainEvent event = factory.organiserRoleAdded(userOf());

        assertThat(event.eventType()).isEqualTo(EventType.ORGANISER_ROLE_ADDED);
        assertThat(event.payload()).containsOnlyKeys("user");
    }

    @Test
    void organiserRoleRemovedCarriesOnlyTheUser() {
        DomainEvent event = factory.organiserRoleRemoved(userOf());

        assertThat(event.eventType()).isEqualTo(EventType.ORGANISER_ROLE_REMOVED);
        assertThat(event.payload()).containsOnlyKeys("user");
    }

    @Test
    void groupFormedCarriesBothGroupAndTopic() {
        DomainEvent event = factory.groupFormed(groupOf(), ComplianceStatus.NOT_COMPLIANT, topicOf());

        assertThat(event.eventType()).isEqualTo(EventType.GROUP_FORMED);
        assertThat(event.payload()).containsOnlyKeys("group", "topic");
    }

    @Test
    void groupDisbandedCarriesBothGroupAndTopic() {
        DomainEvent event = factory.groupDisbanded(groupOf(), null, topicOf());

        assertThat(event.eventType()).isEqualTo(EventType.GROUP_DISBANDED);
        assertThat(event.payload()).containsOnlyKeys("group", "topic");
    }

    @Test
    void groupComplianceChangedCarriesBothGroupAndTopic() {
        DomainEvent event = factory.groupComplianceChanged(groupOf(), ComplianceStatus.COMPLIANT, topicOf());

        assertThat(event.eventType()).isEqualTo(EventType.GROUP_COMPLIANCE_CHANGED);
        assertThat(event.payload()).containsOnlyKeys("group", "topic");
    }

    private static Topic topicOf() {
        Topic topic = new Topic();
        topic.setId(UUID.randomUUID());
        topic.setName("Realtime dashboard");
        topic.setDescription("A live view of team formation.");
        topic.setCreatedByUserId(UUID.randomUUID());
        topic.setApprovalStatus(TopicApprovalStatus.APPROVED);
        topic.setCreatedAt(Instant.now());
        topic.setUpdatedAt(Instant.now());
        return topic;
    }

    private static Participant participantOf() {
        Participant participant = new Participant();
        participant.setId(UUID.randomUUID());
        participant.setUserId(UUID.randomUUID());
        participant.setStatus(ParticipantStatus.ACTIVE);
        participant.setCreatedAt(Instant.now());
        participant.setUpdatedAt(Instant.now());
        return participant;
    }

    private static User userOf() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setOidcSubject("sub-123");
        user.setDisplayName("Jane Doe");
        user.setEmail("jane@example.com");
        user.setOrganiser(false);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return user;
    }

    private void stubUserAndCustomFields(Participant participant, User user, List<CustomFieldAnswer> answers) {
        when(userRepository.findById(participant.getUserId())).thenReturn(Mono.just(user));
        when(customFieldService.currentAnswers(participant.getId())).thenReturn(Mono.just(answers));
    }

    @SuppressWarnings("unchecked")
    private static void assertUserPayloadMatches(java.util.Map<String, Object> payload, User user) {
        var userPayload = (java.util.Map<String, Object>) payload.get("user");
        assertThat(userPayload).containsEntry("id", user.getId()).containsEntry("displayName", user.getDisplayName());
    }

    @SuppressWarnings("unchecked")
    private static void assertCustomFieldsPayloadHasOneAnswerOf(java.util.Map<String, Object> payload, String expected) {
        var customFields = (List<java.util.Map<String, Object>>) payload.get("customFields");
        assertThat(customFields).hasSize(1);
        assertThat(customFields.get(0).get("freeTextValue")).isEqualTo(expected);
    }

    private static CustomFieldAnswer answerOf(String freeTextValue) {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setId(UUID.randomUUID());
        definition.setLabel("T-shirt size");
        definition.setFieldType(CustomFieldType.FREE_TEXT);
        definition.setRequired(true);
        return new CustomFieldAnswer(definition, List.<CustomFieldOption>of(), freeTextValue, List.<UUID>of());
    }

    private static Group groupOf() {
        Group group = new Group();
        group.setId(UUID.randomUUID());
        group.setTopicId(UUID.randomUUID());
        group.setStatus(GroupStatus.ACTIVE);
        group.setComplianceOverride(false);
        group.setCreatedAt(Instant.now());
        group.setUpdatedAt(Instant.now());
        return group;
    }
}

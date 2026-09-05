package net.fabcelhaft.hackathonorganiser.event;

import java.util.LinkedHashMap;
import java.util.Map;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceStatus;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldAnswer;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldOption;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldService;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.topic.Topic;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Builds the {@code Map<String, Object>} payload for a {@link DomainEvent}, reusing each entity's
 * existing field names 1:1 (data-model.md "Event payload shapes"; spec.md Assumptions). Every
 * {@code *Payload} method here corresponds exactly to one row of data-model.md's "Event payload
 * shapes" section; every {@code eventTypeName} composition method corresponds to one row of its
 * "Event Type → payload composition" table (contracts/event-payloads.md has worked examples of
 * each).
 *
 * <p>{@code group}'s {@code complianceStatus} is never computed here — {@link ComplianceStatus}
 * requires a live {@code ComplianceService.evaluate(...)} call the caller (always {@code
 * GroupService}, which already has both the Group and its member ids in hand at every call site)
 * has already made, avoiding a dependency cycle between this package and {@code compliance}.
 *
 * <p>Every Participant-carrying Event Type also carries the associated {@code user} and
 * {@code customFields} (spec.md FR-010c, FR-010d); building those requires the two reactive
 * lookups below, so the five Participant-event builder methods return {@link Mono}&lt;{@link
 * DomainEvent}&gt; rather than a plain {@link DomainEvent} (research.md §10 of feature 007).
 * {@code UserRepository}/{@code CustomFieldService} are depended on directly — never {@code
 * ParticipantService} — because {@code participant} already depends on this package
 * ({@code EventPublisher}/{@code EventPayloadFactory}), so the reverse edge would be a
 * bean-construction cycle.
 */
@Component
public class EventPayloadFactory {

    private final UserRepository userRepository;
    private final CustomFieldService customFieldService;

    public EventPayloadFactory(UserRepository userRepository, CustomFieldService customFieldService) {
        this.userRepository = userRepository;
        this.customFieldService = customFieldService;
    }

    public Map<String, Object> topicPayload(Topic topic) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", topic.getId());
        map.put("name", topic.getName());
        map.put("description", topic.getDescription());
        map.put("createdByUserId", topic.getCreatedByUserId());
        map.put("approvalStatus", topic.getApprovalStatus());
        map.put("createdAt", topic.getCreatedAt());
        map.put("updatedAt", topic.getUpdatedAt());
        return map;
    }

    public Map<String, Object> participantPayload(Participant participant) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", participant.getId());
        map.put("userId", participant.getUserId());
        map.put("status", participant.getStatus());
        map.put("createdAt", participant.getCreatedAt());
        map.put("updatedAt", participant.getUpdatedAt());
        return map;
    }

    public Map<String, Object> userPayload(User user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("displayName", user.getDisplayName());
        map.put("email", user.getEmail());
        map.put("organiser", user.isOrganiser());
        map.put("createdAt", user.getCreatedAt());
        map.put("updatedAt", user.getUpdatedAt());
        return map;
    }

    /** One {@code customFields} array entry (data-model.md "Event payload shapes"; FR-010d). */
    public Map<String, Object> customFieldAnswerPayload(CustomFieldAnswer answer) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("id", answer.definition().getId());
        definition.put("label", answer.definition().getLabel());
        definition.put("fieldType", answer.definition().getFieldType());
        definition.put("required", answer.definition().isRequired());
        definition.put("public", answer.definition().isPublic_());
        definition.put("overview", answer.definition().isOverview());

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("definition", definition);
        map.put(
                "options",
                answer.options().stream().map(this::customFieldOptionPayload).toList());
        map.put("freeTextValue", answer.freeTextValue());
        map.put("selectedOptionIds", answer.selectedOptionIds());
        return map;
    }

    private Map<String, Object> customFieldOptionPayload(CustomFieldOption option) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", option.getId());
        map.put("label", option.getLabel());
        return map;
    }

    public Map<String, Object> groupPayload(Group group, ComplianceStatus complianceStatus) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", group.getId());
        map.put("topicId", group.getTopicId());
        map.put("status", group.getStatus());
        map.put("complianceOverride", group.isComplianceOverride());
        map.put("complianceStatus", complianceStatus);
        map.put("createdAt", group.getCreatedAt());
        map.put("updatedAt", group.getUpdatedAt());
        return map;
    }

    public Mono<DomainEvent> participantRegistered(Participant participant) {
        return enrichedParticipantPayload(participant)
                .map(payload -> new DomainEvent(EventType.PARTICIPANT_REGISTERED, payload));
    }

    public Mono<DomainEvent> participantRevoked(Participant participant) {
        return enrichedParticipantPayload(participant)
                .map(payload -> new DomainEvent(EventType.PARTICIPANT_REVOKED, payload));
    }

    public Mono<DomainEvent> participantNotParticipated(Participant participant) {
        return enrichedParticipantPayload(participant)
                .map(payload -> new DomainEvent(EventType.PARTICIPANT_NOT_PARTICIPATED, payload));
    }

    /**
     * {@code participant} + {@code user} (FR-010c) + {@code customFields} (FR-010d) — the shared
     * enrichment every Participant-carrying Event Type composes on top of (research.md §10 of
     * feature 007).
     */
    private Mono<Map<String, Object>> enrichedParticipantPayload(Participant participant) {
        return Mono.zip(
                        userRepository.findById(participant.getUserId()),
                        customFieldService.currentAnswers(participant.getId()))
                .map(tuple -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("participant", participantPayload(participant));
                    map.put("user", userPayload(tuple.getT1()));
                    map.put(
                            "customFields",
                            tuple.getT2().stream().map(this::customFieldAnswerPayload).toList());
                    return map;
                });
    }

    public DomainEvent userCreated(User user) {
        return new DomainEvent(EventType.USER_CREATED, Map.of("user", userPayload(user)));
    }

    public DomainEvent topicProposed(Topic topic) {
        return new DomainEvent(EventType.TOPIC_PROPOSED, Map.of("topic", topicPayload(topic)));
    }

    public DomainEvent topicApproved(Topic topic) {
        return new DomainEvent(EventType.TOPIC_APPROVED, Map.of("topic", topicPayload(topic)));
    }

    public Mono<DomainEvent> participantJoinedTopic(Topic topic, Participant participant) {
        return enrichedParticipantPayload(participant).map(payload -> {
            Map<String, Object> full = new LinkedHashMap<>();
            full.put("topic", topicPayload(topic));
            full.putAll(payload);
            return new DomainEvent(EventType.PARTICIPANT_JOINED_TOPIC, full);
        });
    }

    public Mono<DomainEvent> participantLeftTopic(Topic topic, Participant participant) {
        return enrichedParticipantPayload(participant).map(payload -> {
            Map<String, Object> full = new LinkedHashMap<>();
            full.put("topic", topicPayload(topic));
            full.putAll(payload);
            return new DomainEvent(EventType.PARTICIPANT_LEFT_TOPIC, full);
        });
    }

    public DomainEvent organiserRoleAdded(User user) {
        return new DomainEvent(EventType.ORGANISER_ROLE_ADDED, Map.of("user", userPayload(user)));
    }

    public DomainEvent organiserRoleRemoved(User user) {
        return new DomainEvent(EventType.ORGANISER_ROLE_REMOVED, Map.of("user", userPayload(user)));
    }

    public DomainEvent groupFormed(Group group, ComplianceStatus complianceStatus, Topic topic) {
        return new DomainEvent(
                EventType.GROUP_FORMED,
                Map.of("group", groupPayload(group, complianceStatus), "topic", topicPayload(topic)));
    }

    public DomainEvent groupDisbanded(Group group, ComplianceStatus complianceStatus, Topic topic) {
        return new DomainEvent(
                EventType.GROUP_DISBANDED,
                Map.of("group", groupPayload(group, complianceStatus), "topic", topicPayload(topic)));
    }

    public DomainEvent groupComplianceChanged(Group group, ComplianceStatus complianceStatus, Topic topic) {
        return new DomainEvent(
                EventType.GROUP_COMPLIANCE_CHANGED,
                Map.of("group", groupPayload(group, complianceStatus), "topic", topicPayload(topic)));
    }
}

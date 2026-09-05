package net.fabcelhaft.hackathonorganiser.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceService;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceStatus;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupRepository;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
import net.fabcelhaft.hackathonorganiser.group.GroupStatus;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldService;
import net.fabcelhaft.hackathonorganiser.topic.Topic;
import net.fabcelhaft.hackathonorganiser.topic.TopicRepository;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link ComplianceChangeEventHook} (spec.md FR-010b; research.md §5): a Compliance
 * Ruleset change re-evaluates every active Group and publishes {@code GROUP_COMPLIANCE_CHANGED}
 * only for the ones whose status actually flips.
 */
@ExtendWith(MockitoExtension.class)
class ComplianceChangeEventHookTest {

    @Mock
    private GroupRepository groupRepository;

    @Mock
    private GroupService groupService;

    @Mock
    private ComplianceService complianceService;

    @Mock
    private TopicRepository topicRepository;

    @Mock
    private EventPublisher eventPublisher;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CustomFieldService customFieldService;

    private EventPayloadFactory eventPayloadFactory;

    private ComplianceChangeEventHook hook;

    @BeforeEach
    void setUp() {
        eventPayloadFactory = new EventPayloadFactory(userRepository, customFieldService);
        hook = new ComplianceChangeEventHook(
                groupRepository, groupService, complianceService, topicRepository, eventPublisher, eventPayloadFactory);
    }

    @Test
    void publishesGroupComplianceChangedOnlyForAGroupWhoseStatusFlips() {
        Group flips = groupOf(GroupStatus.ACTIVE);
        Group staysTheSame = groupOf(GroupStatus.ACTIVE);
        when(groupRepository.findAll()).thenReturn(Flux.just(flips, staysTheSame));
        when(groupService.activeMemberParticipantIds(flips.getId())).thenReturn(Mono.just(List.of()));
        when(groupService.activeMemberParticipantIds(staysTheSame.getId())).thenReturn(Mono.just(List.of()));
        // Before the save: both are NOT_COMPLIANT. After: "flips" becomes COMPLIANT.
        when(complianceService.evaluate(eq(flips), any()))
                .thenReturn(Mono.just(ComplianceStatus.NOT_COMPLIANT))
                .thenReturn(Mono.just(ComplianceStatus.COMPLIANT));
        when(complianceService.evaluate(eq(staysTheSame), any())).thenReturn(Mono.just(ComplianceStatus.NOT_COMPLIANT));
        when(topicRepository.findById(flips.getTopicId())).thenReturn(Mono.just(topicOf(flips.getTopicId())));

        Mono<String> save = Mono.just("saved");
        StepVerifier.create(hook.wrapRulesetChange(save)).expectNext("saved").verifyComplete();

        verify(eventPublisher).publish(argThatMatchesGroupComplianceChanged(flips.getId()));
        verify(eventPublisher, never()).publish(argThatMatchesGroupComplianceChanged(staysTheSame.getId()));
    }

    @Test
    void publishesNothingWhenNoGroupsStatusChanges() {
        Group unaffected = groupOf(GroupStatus.ACTIVE);
        when(groupRepository.findAll()).thenReturn(Flux.just(unaffected));
        when(groupService.activeMemberParticipantIds(unaffected.getId())).thenReturn(Mono.just(List.of()));
        when(complianceService.evaluate(eq(unaffected), any())).thenReturn(Mono.just(ComplianceStatus.COMPLIANT));

        Mono<String> save = Mono.just("saved");
        StepVerifier.create(hook.wrapRulesetChange(save)).expectNext("saved").verifyComplete();

        verify(eventPublisher, never()).publish(any(DomainEvent.class));
    }

    @Test
    void ignoresDisbandedGroups() {
        Group disbanded = groupOf(GroupStatus.DISBANDED);
        when(groupRepository.findAll()).thenReturn(Flux.just(disbanded));

        Mono<String> save = Mono.just("saved");
        StepVerifier.create(hook.wrapRulesetChange(save)).expectNext("saved").verifyComplete();

        verify(groupService, never()).activeMemberParticipantIds(disbanded.getId());
        verify(eventPublisher, never()).publish(any(DomainEvent.class));
    }

    @Test
    void stillReturnsTheSaveResultWhenTheSnapshotMachineryFails() {
        when(groupRepository.findAll()).thenReturn(Flux.error(new RuntimeException("boom")));

        Mono<String> save = Mono.just("saved");
        StepVerifier.create(hook.wrapRulesetChange(save)).expectNext("saved").verifyComplete();
    }

    @Test
    void propagatesAFailingSaveUnchanged() {
        when(groupRepository.findAll()).thenReturn(Flux.empty());

        Mono<String> failingSave = Mono.error(new RuntimeException("save failed"));
        StepVerifier.create(hook.wrapRulesetChange(failingSave)).expectErrorMessage("save failed").verify();
    }

    @SuppressWarnings("unchecked")
    private static DomainEvent argThatMatchesGroupComplianceChanged(UUID groupId) {
        return org.mockito.ArgumentMatchers.argThat(event -> {
            if (event == null || event.eventType() != EventType.GROUP_COMPLIANCE_CHANGED) {
                return false;
            }
            Map<String, Object> groupPayload = (Map<String, Object>) event.payload().get("group");
            return groupId.equals(groupPayload.get("id"));
        });
    }

    private static Group groupOf(GroupStatus status) {
        Group group = new Group();
        group.setId(UUID.randomUUID());
        group.setTopicId(UUID.randomUUID());
        group.setStatus(status);
        group.setComplianceOverride(false);
        group.setCreatedAt(Instant.now());
        group.setUpdatedAt(Instant.now());
        return group;
    }

    private static Topic topicOf(UUID id) {
        Topic topic = new Topic();
        topic.setId(id);
        topic.setName("Topic");
        topic.setDescription("Description");
        topic.setCreatedByUserId(UUID.randomUUID());
        topic.setCreatedAt(Instant.now());
        topic.setUpdatedAt(Instant.now());
        return topic;
    }
}

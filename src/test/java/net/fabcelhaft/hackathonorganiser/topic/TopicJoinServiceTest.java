package net.fabcelhaft.hackathonorganiser.topic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettings;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantService;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link TopicJoinService#join} (T036; data-model.md, FR-007b, FR-020b, FR-020c):
 * the eligibility gate — Topic joining enabled, requester is an Active Participant, Topic is
 * Approved — evaluated in order, each rejection a distinct message, before delegating to the
 * race-safe {@link GroupService#join}. Per Constitution Development Workflow #4, the
 * multi-operator reactive chain under test is verified with {@link StepVerifier}, never
 * {@code .block()}.
 */
@ExtendWith(MockitoExtension.class)
class TopicJoinServiceTest {

    @Mock
    private OrganiserSettingsService organiserSettingsService;

    @Mock
    private ParticipantService participantService;

    @Mock
    private TopicService topicService;

    @Mock
    private GroupService groupService;

    private TopicJoinService topicJoinService;

    @BeforeEach
    void setUp() {
        topicJoinService =
                new TopicJoinService(organiserSettingsService, participantService, topicService, groupService);
    }

    @Test
    void joinRejectsWhenTopicJoiningIsDisabled() {
        UUID topicId = UUID.randomUUID();
        UUID requesterUserId = UUID.randomUUID();
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(false)));

        StepVerifier.create(topicJoinService.join(topicId, requesterUserId))
                .expectError(TopicJoinConflictException.class)
                .verify();

        verify(participantService, never()).findByUserId(any());
        verify(groupService, never()).join(any(), any());
    }

    @Test
    void joinRejectsARequesterWithNoParticipantRecord() {
        UUID topicId = UUID.randomUUID();
        UUID requesterUserId = UUID.randomUUID();
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true)));
        when(participantService.findByUserId(requesterUserId)).thenReturn(Mono.empty());

        StepVerifier.create(topicJoinService.join(topicId, requesterUserId))
                .expectError(TopicJoinConflictException.class)
                .verify();

        verify(groupService, never()).join(any(), any());
    }

    @Test
    void joinRejectsANonActiveParticipant() {
        UUID topicId = UUID.randomUUID();
        UUID requesterUserId = UUID.randomUUID();
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true)));
        when(participantService.findByUserId(requesterUserId))
                .thenReturn(Mono.just(participantOf(requesterUserId, ParticipantStatus.REVOKED)));

        StepVerifier.create(topicJoinService.join(topicId, requesterUserId))
                .expectError(TopicJoinConflictException.class)
                .verify();

        verify(groupService, never()).join(any(), any());
    }

    @Test
    void joinCompletesEmptyForAnUnknownTopic() {
        UUID topicId = UUID.randomUUID();
        UUID requesterUserId = UUID.randomUUID();
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true)));
        when(participantService.findByUserId(requesterUserId))
                .thenReturn(Mono.just(participantOf(requesterUserId, ParticipantStatus.ACTIVE)));
        when(topicService.findById(topicId)).thenReturn(Mono.empty());

        StepVerifier.create(topicJoinService.join(topicId, requesterUserId)).verifyComplete();

        verify(groupService, never()).join(any(), any());
    }

    @Test
    void joinCompletesEmptyForAPendingTopic() {
        UUID topicId = UUID.randomUUID();
        UUID requesterUserId = UUID.randomUUID();
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true)));
        when(participantService.findByUserId(requesterUserId))
                .thenReturn(Mono.just(participantOf(requesterUserId, ParticipantStatus.ACTIVE)));
        when(topicService.findById(topicId)).thenReturn(Mono.just(topicOf(topicId, TopicApprovalStatus.PENDING)));

        StepVerifier.create(topicJoinService.join(topicId, requesterUserId)).verifyComplete();

        verify(groupService, never()).join(any(), any());
    }

    @Test
    void joinDelegatesToGroupServiceForAnEligibleRequesterAndApprovedTopic() {
        UUID topicId = UUID.randomUUID();
        UUID requesterUserId = UUID.randomUUID();
        Participant participant = participantOf(requesterUserId, ParticipantStatus.ACTIVE);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true)));
        when(participantService.findByUserId(requesterUserId)).thenReturn(Mono.just(participant));
        when(topicService.findById(topicId)).thenReturn(Mono.just(topicOf(topicId, TopicApprovalStatus.APPROVED)));
        Group group = new Group();
        group.setId(UUID.randomUUID());
        when(groupService.join(topicId, participant.getId())).thenReturn(Mono.just(group));

        StepVerifier.create(topicJoinService.join(topicId, requesterUserId))
                .expectNext(group)
                .verifyComplete();
    }

    // --- leave (Story 11, FR-037b, FR-037e) -----------------------------------------------------

    @Test
    void leaveRejectsARequesterWithNoParticipantRecord() {
        UUID topicId = UUID.randomUUID();
        UUID requesterUserId = UUID.randomUUID();
        when(participantService.findByUserId(requesterUserId)).thenReturn(Mono.empty());

        StepVerifier.create(topicJoinService.leave(topicId, requesterUserId))
                .expectError(TopicJoinConflictException.class)
                .verify();

        verify(groupService, never()).leave(any(), any());
    }

    @Test
    void leaveRejectsWhenRequesterHasNoActiveGroup() {
        UUID topicId = UUID.randomUUID();
        UUID requesterUserId = UUID.randomUUID();
        Participant participant = participantOf(requesterUserId, ParticipantStatus.ACTIVE);
        when(participantService.findByUserId(requesterUserId)).thenReturn(Mono.just(participant));
        when(groupService.findActiveGroupForParticipant(participant.getId())).thenReturn(Mono.empty());

        StepVerifier.create(topicJoinService.leave(topicId, requesterUserId))
                .expectError(TopicJoinConflictException.class)
                .verify();

        verify(groupService, never()).leave(any(), any());
    }

    @Test
    void leaveRejectsWhenRequestersActiveGroupIsForADifferentTopic() {
        UUID topicId = UUID.randomUUID();
        UUID otherTopicId = UUID.randomUUID();
        UUID requesterUserId = UUID.randomUUID();
        Participant participant = participantOf(requesterUserId, ParticipantStatus.ACTIVE);
        when(participantService.findByUserId(requesterUserId)).thenReturn(Mono.just(participant));
        Group activeGroupForOtherTopic = new Group();
        activeGroupForOtherTopic.setId(UUID.randomUUID());
        activeGroupForOtherTopic.setTopicId(otherTopicId);
        when(groupService.findActiveGroupForParticipant(participant.getId()))
                .thenReturn(Mono.just(activeGroupForOtherTopic));

        StepVerifier.create(topicJoinService.leave(topicId, requesterUserId))
                .expectError(TopicJoinConflictException.class)
                .verify();

        verify(groupService, never()).leave(any(), any());
    }

    @Test
    void leaveDelegatesToGroupServiceRegardlessOfParticipantStatusOrTopicJoiningSetting() {
        UUID topicId = UUID.randomUUID();
        UUID requesterUserId = UUID.randomUUID();
        // A Revoked/Not-Participated Participant already has no active Group by construction (their
        // self-revocation flow removes it as a side effect) — but this test proves leave() itself
        // applies no separate status gate (FR-037e), unlike join()'s requireActive check.
        Participant participant = participantOf(requesterUserId, ParticipantStatus.REVOKED);
        when(participantService.findByUserId(requesterUserId)).thenReturn(Mono.just(participant));
        Group activeGroup = new Group();
        activeGroup.setId(UUID.randomUUID());
        activeGroup.setTopicId(topicId);
        when(groupService.findActiveGroupForParticipant(participant.getId())).thenReturn(Mono.just(activeGroup));
        Group afterLeave = new Group();
        afterLeave.setId(activeGroup.getId());
        when(groupService.leave(topicId, participant.getId())).thenReturn(Mono.just(afterLeave));

        StepVerifier.create(topicJoinService.leave(topicId, requesterUserId))
                .expectNext(afterLeave)
                .verifyComplete();

        verify(organiserSettingsService, never()).current();
    }

    // --- test helpers ------------------------------------------------------------------------------

    private OrganiserSettings settingsOf(boolean topicJoiningEnabled) {
        OrganiserSettings settings = new OrganiserSettings();
        settings.setId(UUID.randomUUID());
        settings.setTopicJoiningEnabled(topicJoiningEnabled);
        settings.setUpdatedAt(Instant.now());
        return settings;
    }

    private Participant participantOf(UUID userId, ParticipantStatus status) {
        Participant participant = new Participant();
        participant.setId(UUID.randomUUID());
        participant.setUserId(userId);
        participant.setStatus(status);
        Instant now = Instant.now();
        participant.setCreatedAt(now);
        participant.setUpdatedAt(now);
        return participant;
    }

    private Topic topicOf(UUID id, TopicApprovalStatus status) {
        Topic topic = new Topic();
        topic.setId(id);
        topic.setName("Topic " + id);
        topic.setDescription("Description");
        topic.setCreatedByUserId(UUID.randomUUID());
        topic.setApprovalStatus(status);
        Instant now = Instant.now();
        topic.setCreatedAt(now);
        topic.setUpdatedAt(now);
        return topic;
    }
}

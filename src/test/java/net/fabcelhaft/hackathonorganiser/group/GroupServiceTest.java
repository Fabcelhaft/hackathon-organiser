package net.fabcelhaft.hackathonorganiser.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantRepository;
import net.fabcelhaft.hackathonorganiser.topic.TopicRepository;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link GroupService} (T052): create blocked when a Topic already has an active
 * Group (FR-016a), add-member blocked when a Participant already has a different active Group
 * (FR-017), and disband flipping the Group {@code DISBANDED} and every membership {@code
 * active=false} (FR-016b). Per Constitution Development Workflow #4, the multi-operator reactive
 * chains under test (lookup -> conditional branch -> write) are verified with {@link
 * StepVerifier}, never {@code .block()}.
 */
@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

    @Mock
    private GroupRepository groupRepository;

    @Mock
    private TopicRepository topicRepository;

    @Mock
    private ParticipantRepository participantRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private DatabaseClient.GenericExecuteSpec executeSpec;

    private GroupService groupService;

    @BeforeEach
    void setUp() {
        groupService = new GroupService(
                groupRepository, topicRepository, participantRepository, userRepository, databaseClient);
    }

    // --- create: blocked when the Topic already has an active Group (FR-016a) ------------------

    @Test
    void createRejectsATopicThatAlreadyHasAnActiveGroup() {
        UUID topicId = UUID.randomUUID();
        Group existingActive = groupOf(UUID.randomUUID(), topicId, GroupStatus.ACTIVE);
        when(topicRepository.existsById(topicId)).thenReturn(Mono.just(true));
        when(groupRepository.findByTopicIdAndStatus(topicId, GroupStatus.ACTIVE))
                .thenReturn(Mono.just(existingActive));

        StepVerifier.create(groupService.create(topicId, List.of()))
                .expectError(GroupConflictException.class)
                .verify();

        verify(groupRepository, never()).save(any());
    }

    @Test
    void createRejectsAnUnknownTopicId() {
        UUID topicId = UUID.randomUUID();
        when(topicRepository.existsById(topicId)).thenReturn(Mono.just(false));

        StepVerifier.create(groupService.create(topicId, List.of())).verifyComplete();

        verify(groupRepository, never()).save(any());
    }

    @Test
    void createRejectsAMissingTopicId() {
        StepVerifier.create(groupService.create(null, List.of()))
                .expectError(GroupConflictException.class)
                .verify();

        verify(topicRepository, never()).existsById(any(UUID.class));
        verify(groupRepository, never()).save(any());
    }

    @Test
    void createSucceedsForATopicWithNoActiveGroupAndNoInitialMembers() {
        UUID topicId = UUID.randomUUID();
        when(topicRepository.existsById(topicId)).thenReturn(Mono.just(true));
        when(groupRepository.findByTopicIdAndStatus(topicId, GroupStatus.ACTIVE)).thenReturn(Mono.empty());
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> {
            Group group = invocation.getArgument(0);
            group.setId(UUID.randomUUID());
            return Mono.just(group);
        });

        StepVerifier.create(groupService.create(topicId, List.of()))
                .assertNext(group -> {
                    assertThat(group.getTopicId()).isEqualTo(topicId);
                    assertThat(group.getStatus()).isEqualTo(GroupStatus.ACTIVE);
                    assertThat(group.getCreatedAt()).isNotNull();
                    assertThat(group.getUpdatedAt()).isNotNull();
                })
                .verifyComplete();
    }

    // --- addMember: blocked when the Participant already has a different active Group (FR-017) -

    @Test
    void addMemberRejectsAParticipantAlreadyInADifferentActiveGroup() {
        UUID groupId = UUID.randomUUID();
        UUID otherGroupId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Group group = groupOf(groupId, UUID.randomUUID(), GroupStatus.ACTIVE);
        when(groupRepository.findById(groupId)).thenReturn(Mono.just(group));
        when(participantRepository.existsById(participantId)).thenReturn(Mono.just(true));
        stubActiveGroupIdForParticipant(participantId, otherGroupId);

        StepVerifier.create(groupService.addMember(groupId, participantId))
                .expectError(GroupConflictException.class)
                .verify();
    }

    @Test
    void addMemberRejectsAnUnknownParticipant() {
        UUID groupId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Group group = groupOf(groupId, UUID.randomUUID(), GroupStatus.ACTIVE);
        when(groupRepository.findById(groupId)).thenReturn(Mono.just(group));
        when(participantRepository.existsById(participantId)).thenReturn(Mono.just(false));

        StepVerifier.create(groupService.addMember(groupId, participantId))
                .expectError(GroupConflictException.class)
                .verify();
    }

    @Test
    void addMemberRejectsOnADisbandedGroup() {
        UUID groupId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Group disbanded = groupOf(groupId, UUID.randomUUID(), GroupStatus.DISBANDED);
        when(groupRepository.findById(groupId)).thenReturn(Mono.just(disbanded));

        StepVerifier.create(groupService.addMember(groupId, participantId))
                .expectError(GroupConflictException.class)
                .verify();

        verify(participantRepository, never()).existsById(any(UUID.class));
    }

    @Test
    void addMemberOfAnUnknownGroupCompletesEmpty() {
        UUID groupId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        when(groupRepository.findById(groupId)).thenReturn(Mono.empty());

        StepVerifier.create(groupService.addMember(groupId, participantId)).verifyComplete();
    }

    @Test
    void addMemberSucceedsWhenParticipantHasNoOtherActiveGroup() {
        UUID groupId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Group group = groupOf(groupId, UUID.randomUUID(), GroupStatus.ACTIVE);
        when(groupRepository.findById(groupId)).thenReturn(Mono.just(group));
        when(participantRepository.existsById(participantId)).thenReturn(Mono.just(true));
        stubNoActiveGroupForParticipant(participantId);
        stubWriteAlwaysSucceeds();

        StepVerifier.create(groupService.addMember(groupId, participantId))
                .expectNext(group)
                .verifyComplete();
    }

    @Test
    void addMemberSucceedsWhenParticipantsOnlyActiveGroupIsThisSameGroup() {
        UUID groupId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Group group = groupOf(groupId, UUID.randomUUID(), GroupStatus.ACTIVE);
        when(groupRepository.findById(groupId)).thenReturn(Mono.just(group));
        when(participantRepository.existsById(participantId)).thenReturn(Mono.just(true));
        stubActiveGroupIdForParticipant(participantId, groupId);
        stubWriteAlwaysSucceeds();

        StepVerifier.create(groupService.addMember(groupId, participantId))
                .expectNext(group)
                .verifyComplete();
    }

    // --- disband: flips the Group DISBANDED and every membership active=false (FR-016b) --------

    @Test
    void disbandFlipsTheGroupToDisbandedAndClearsMemberships() {
        UUID groupId = UUID.randomUUID();
        Group active = groupOf(groupId, UUID.randomUUID(), GroupStatus.ACTIVE);
        when(groupRepository.findById(groupId)).thenReturn(Mono.just(active));
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        stubWriteAlwaysSucceeds();

        StepVerifier.create(groupService.disband(groupId))
                .assertNext(group -> {
                    assertThat(group.getStatus()).isEqualTo(GroupStatus.DISBANDED);
                    assertThat(group.getDisbandedAt()).isNotNull();
                })
                .verifyComplete();

        verify(databaseClient).sql(org.mockito.ArgumentMatchers.contains("UPDATE group_members SET active = false"));
    }

    @Test
    void disbandOfAnUnknownGroupCompletesEmpty() {
        UUID groupId = UUID.randomUUID();
        when(groupRepository.findById(groupId)).thenReturn(Mono.empty());

        StepVerifier.create(groupService.disband(groupId)).verifyComplete();

        verify(groupRepository, never()).save(any());
    }

    @Test
    void disbandingAnAlreadyDisbandedGroupFails() {
        UUID groupId = UUID.randomUUID();
        Group disbanded = groupOf(groupId, UUID.randomUUID(), GroupStatus.DISBANDED);
        when(groupRepository.findById(groupId)).thenReturn(Mono.just(disbanded));

        StepVerifier.create(groupService.disband(groupId))
                .expectError(GroupConflictException.class)
                .verify();

        verify(groupRepository, never()).save(any());
    }

    // --- removeMember: only an active membership can be removed ----------------------------------

    @Test
    void removeMemberOfANonActiveMembershipCompletesEmpty() {
        UUID groupId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Group group = groupOf(groupId, UUID.randomUUID(), GroupStatus.ACTIVE);
        when(groupRepository.findById(groupId)).thenReturn(Mono.just(group));
        stubIsActiveMember(false);

        StepVerifier.create(groupService.removeMember(groupId, participantId)).verifyComplete();
    }

    @Test
    void removeMemberOfAnActiveMembershipSucceeds() {
        UUID groupId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Group group = groupOf(groupId, UUID.randomUUID(), GroupStatus.ACTIVE);
        when(groupRepository.findById(groupId)).thenReturn(Mono.just(group));
        stubIsActiveMember(true);
        stubWriteAlwaysSucceeds();

        StepVerifier.create(groupService.removeMember(groupId, participantId))
                .expectNext(group)
                .verifyComplete();
    }

    // --- test helpers ------------------------------------------------------------------------------

    private Group groupOf(UUID id, UUID topicId, GroupStatus status) {
        Group group = new Group();
        group.setId(id);
        group.setTopicId(topicId);
        group.setStatus(status);
        group.setCreatedAt(Instant.now());
        group.setUpdatedAt(Instant.now());
        if (status == GroupStatus.DISBANDED) {
            group.setDisbandedAt(Instant.now());
        }
        return group;
    }

    private void stubWriteAlwaysSucceeds() {
        lenient().when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        lenient().when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        lenient().when(executeSpec.then()).thenReturn(Mono.empty());
    }

    @SuppressWarnings("unchecked")
    private void stubActiveGroupIdForParticipant(UUID participantId, UUID activeGroupId) {
        RowsFetchSpec<UUID> fetch = mock(RowsFetchSpec.class);
        lenient().when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        lenient().when(executeSpec.bind(eq("pid"), any())).thenReturn(executeSpec);
        when(executeSpec.mapValue(UUID.class)).thenReturn(fetch);
        when(fetch.one()).thenReturn(Mono.just(activeGroupId));
    }

    @SuppressWarnings("unchecked")
    private void stubNoActiveGroupForParticipant(UUID participantId) {
        RowsFetchSpec<UUID> fetch = mock(RowsFetchSpec.class);
        lenient().when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        lenient().when(executeSpec.bind(eq("pid"), any())).thenReturn(executeSpec);
        when(executeSpec.mapValue(UUID.class)).thenReturn(fetch);
        when(fetch.one()).thenReturn(Mono.empty());
    }

    @SuppressWarnings("unchecked")
    private void stubIsActiveMember(boolean active) {
        RowsFetchSpec<Boolean> fetch = mock(RowsFetchSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.mapValue(Boolean.class)).thenReturn(fetch);
        when(fetch.one()).thenReturn(active ? Mono.just(true) : Mono.empty());
        if (active) {
            when(executeSpec.then()).thenReturn(Mono.empty());
        }
    }
}

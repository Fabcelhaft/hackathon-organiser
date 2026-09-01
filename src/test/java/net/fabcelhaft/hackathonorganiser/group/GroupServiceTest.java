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
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceService;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettings;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
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
import org.springframework.transaction.reactive.TransactionalOperator;
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

    @Mock
    private OrganiserSettingsService organiserSettingsService;

    @Mock
    private ComplianceService complianceService;

    private GroupService groupService;

    @BeforeEach
    void setUp() {
        // A pass-through TransactionalOperator: unit tests exercise business logic, not real
        // reactive-transaction semantics (that's *ManagementIT's job, against a real Postgres).
        TransactionalOperator transactionalOperator = new TransactionalOperator() {
            @Override
            public <T> reactor.core.publisher.Flux<T> execute(
                    org.springframework.transaction.reactive.TransactionCallback<T> action) {
                throw new UnsupportedOperationException("not used by these tests");
            }

            @Override
            public <T> Mono<T> transactional(Mono<T> mono) {
                return mono;
            }
        };
        groupService = new GroupService(
                groupRepository,
                topicRepository,
                participantRepository,
                userRepository,
                databaseClient,
                organiserSettingsService,
                transactionalOperator,
                complianceService);
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

    // --- findActiveGroupForParticipant (research.md §10, FR-007a) --------------------------------

    @Test
    void findActiveGroupForParticipantReturnsTheParticipantsActiveGroup() {
        UUID participantId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        Group group = groupOf(groupId, UUID.randomUUID(), GroupStatus.ACTIVE);
        stubActiveGroupIdForParticipant(participantId, groupId);
        when(groupRepository.findById(groupId)).thenReturn(Mono.just(group));

        StepVerifier.create(groupService.findActiveGroupForParticipant(participantId))
                .expectNext(group)
                .verifyComplete();
    }

    @Test
    void findActiveGroupForParticipantCompletesEmptyWhenNoneExists() {
        UUID participantId = UUID.randomUUID();
        stubNoActiveGroupForParticipant(participantId);

        StepVerifier.create(groupService.findActiveGroupForParticipant(participantId)).verifyComplete();

        verify(groupRepository, never()).findById(any(UUID.class));
    }

    // --- join: race-safe create-or-grow under capacity/override (Story 3, FR-007-FR-013a) -------

    @Test
    void joinCreatesANewGroupWithTheParticipantAsSoleMemberWhenNoneExistsYet() {
        UUID topicId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        when(groupRepository.findByTopicIdAndStatus(topicId, GroupStatus.ACTIVE)).thenReturn(Mono.empty());
        when(topicRepository.existsById(topicId)).thenReturn(Mono.just(true));
        java.util.concurrent.atomic.AtomicReference<Group> savedGroupRef = new java.util.concurrent.atomic.AtomicReference<>();
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> {
            Group group = invocation.getArgument(0);
            group.setId(UUID.randomUUID());
            savedGroupRef.set(group);
            return Mono.just(group);
        });
        when(groupRepository.findById(any(UUID.class))).thenAnswer(invocation -> Mono.just(savedGroupRef.get()));
        when(participantRepository.existsById(participantId)).thenReturn(Mono.just(true));
        stubNoActiveGroupForParticipant(participantId);
        stubWriteAlwaysSucceeds();

        StepVerifier.create(groupService.join(topicId, participantId))
                .assertNext(group -> {
                    assertThat(group.getTopicId()).isEqualTo(topicId);
                    assertThat(group.getStatus()).isEqualTo(GroupStatus.ACTIVE);
                })
                .verifyComplete();
    }

    @Test
    void joinAddsToAnExistingGroupUnderCapacity() {
        UUID topicId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Group existing = groupOf(UUID.randomUUID(), topicId, GroupStatus.ACTIVE);
        when(groupRepository.findByTopicIdAndStatus(topicId, GroupStatus.ACTIVE)).thenReturn(Mono.just(existing));
        when(groupRepository.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsWithMax(5)));
        stubCount(existing.getId(), 2);
        when(participantRepository.existsById(participantId)).thenReturn(Mono.just(true));
        stubNoActiveGroupForParticipant(participantId);
        stubWriteAlwaysSucceeds();

        StepVerifier.create(groupService.join(topicId, participantId))
                .expectNext(existing)
                .verifyComplete();
    }

    @Test
    void joinRejectsWhenAtMaxGroupMembersAndNoOverride() {
        UUID topicId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Group existing = groupOf(UUID.randomUUID(), topicId, GroupStatus.ACTIVE);
        when(groupRepository.findByTopicIdAndStatus(topicId, GroupStatus.ACTIVE)).thenReturn(Mono.just(existing));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsWithMax(3)));
        stubCount(existing.getId(), 3);
        stubWriteAlwaysSucceeds();

        StepVerifier.create(groupService.join(topicId, participantId))
                .expectErrorSatisfies(ex -> assertThat(ex)
                        .isInstanceOf(GroupConflictException.class)
                        .hasMessageContaining("full"))
                .verify();

        verify(groupRepository, never()).findById(any(UUID.class));
    }

    @Test
    void joinSucceedsAtMaxGroupMembersWithComplianceOverride() {
        UUID topicId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Group existing = groupOf(UUID.randomUUID(), topicId, GroupStatus.ACTIVE);
        existing.setComplianceOverride(true);
        when(groupRepository.findByTopicIdAndStatus(topicId, GroupStatus.ACTIVE)).thenReturn(Mono.just(existing));
        when(groupRepository.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsWithMax(3)));
        stubCount(existing.getId(), 3);
        when(participantRepository.existsById(participantId)).thenReturn(Mono.just(true));
        stubNoActiveGroupForParticipant(participantId);
        stubWriteAlwaysSucceeds();

        StepVerifier.create(groupService.join(topicId, participantId))
                .expectNext(existing)
                .verifyComplete();
    }

    @Test
    void joinRejectsAParticipantAlreadyInADifferentActiveGroup() {
        UUID topicId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        UUID otherGroupId = UUID.randomUUID();
        Group existing = groupOf(UUID.randomUUID(), topicId, GroupStatus.ACTIVE);
        when(groupRepository.findByTopicIdAndStatus(topicId, GroupStatus.ACTIVE)).thenReturn(Mono.just(existing));
        when(groupRepository.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsWithMax(5)));
        stubCount(existing.getId(), 2);
        when(participantRepository.existsById(participantId)).thenReturn(Mono.just(true));
        stubActiveGroupIdForParticipant(participantId, otherGroupId);
        stubWriteAlwaysSucceeds();

        StepVerifier.create(groupService.join(topicId, participantId))
                .expectError(GroupConflictException.class)
                .verify();
    }

    @Test
    void joinOfAnUnknownTopicCompletesEmpty() {
        UUID topicId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        when(groupRepository.findByTopicIdAndStatus(topicId, GroupStatus.ACTIVE)).thenReturn(Mono.empty());
        when(topicRepository.existsById(topicId)).thenReturn(Mono.just(false));
        stubWriteAlwaysSucceeds();

        StepVerifier.create(groupService.join(topicId, participantId)).verifyComplete();

        verify(groupRepository, never()).save(any());
    }

    // --- leave: single-member removal + auto-disband when last member (Story 11, FR-037b/c) -----

    @Test
    void leaveRejectsWhenNoActiveGroupExistsForTheTopic() {
        UUID topicId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        when(groupRepository.findByTopicIdAndStatus(topicId, GroupStatus.ACTIVE)).thenReturn(Mono.empty());
        stubWriteAlwaysSucceeds();

        StepVerifier.create(groupService.leave(topicId, participantId))
                .expectErrorSatisfies(ex -> assertThat(ex)
                        .isInstanceOf(GroupConflictException.class)
                        .hasMessageContaining("not currently a member"))
                .verify();
    }

    @Test
    void leaveRejectsWhenRequesterIsNotAnActiveMemberOfTheGroup() {
        UUID topicId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Group existing = groupOf(UUID.randomUUID(), topicId, GroupStatus.ACTIVE);
        when(groupRepository.findByTopicIdAndStatus(topicId, GroupStatus.ACTIVE)).thenReturn(Mono.just(existing));
        when(groupRepository.findById(existing.getId())).thenReturn(Mono.just(existing));
        stubWriteAlwaysSucceeds();
        stubIsActiveMember(false);

        StepVerifier.create(groupService.leave(topicId, participantId))
                .expectErrorSatisfies(ex -> assertThat(ex)
                        .isInstanceOf(GroupConflictException.class)
                        .hasMessageContaining("not currently a member"))
                .verify();
    }

    @Test
    void leaveRemovesAnActiveMemberAndKeepsTheGroupActiveWhenOthersRemain() {
        UUID topicId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Group existing = groupOf(UUID.randomUUID(), topicId, GroupStatus.ACTIVE);
        when(groupRepository.findByTopicIdAndStatus(topicId, GroupStatus.ACTIVE)).thenReturn(Mono.just(existing));
        when(groupRepository.findById(existing.getId())).thenReturn(Mono.just(existing));
        stubIsActiveMember(true);
        stubCount(existing.getId(), 1);
        stubWriteAlwaysSucceeds();

        StepVerifier.create(groupService.leave(topicId, participantId))
                .assertNext(group -> {
                    assertThat(group.getId()).isEqualTo(existing.getId());
                    assertThat(group.getStatus()).isEqualTo(GroupStatus.ACTIVE);
                })
                .verifyComplete();

        verify(groupRepository, never()).save(any());
    }

    @Test
    void leaveDisbandsTheGroupWhenTheRequesterWasTheLastActiveMember() {
        UUID topicId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Group existing = groupOf(UUID.randomUUID(), topicId, GroupStatus.ACTIVE);
        when(groupRepository.findByTopicIdAndStatus(topicId, GroupStatus.ACTIVE)).thenReturn(Mono.just(existing));
        when(groupRepository.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        stubIsActiveMember(true);
        stubCount(existing.getId(), 0);
        stubWriteAlwaysSucceeds();

        StepVerifier.create(groupService.leave(topicId, participantId))
                .assertNext(group -> {
                    assertThat(group.getStatus()).isEqualTo(GroupStatus.DISBANDED);
                    assertThat(group.getDisbandedAt()).isNotNull();
                })
                .verifyComplete();

        verify(groupRepository).save(any(Group.class));
    }

    // Two concurrent leave(...) calls against a Testcontainers-backed Postgres connection, where one
    // is the Group's last remaining member, must result in disbandment applied exactly once — that
    // assertion needs a real advisory lock and a real database, exactly like join's own concurrency
    // case (T031's note above), so it lives in TopicDetailManagementIT (T090/T091), not here.

    // --- setComplianceOverride (Story 7, FR-015, FR-016) ------------------------------------------

    @Test
    void setComplianceOverrideSetsTheFlag() {
        UUID groupId = UUID.randomUUID();
        Group group = groupOf(groupId, UUID.randomUUID(), GroupStatus.ACTIVE);
        when(groupRepository.findById(groupId)).thenReturn(Mono.just(group));
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(groupService.setComplianceOverride(groupId, true))
                .assertNext(saved -> assertThat(saved.isComplianceOverride()).isTrue())
                .verifyComplete();
    }

    @Test
    void setComplianceOverrideClearsTheFlag() {
        UUID groupId = UUID.randomUUID();
        Group group = groupOf(groupId, UUID.randomUUID(), GroupStatus.ACTIVE);
        group.setComplianceOverride(true);
        when(groupRepository.findById(groupId)).thenReturn(Mono.just(group));
        when(groupRepository.save(any(Group.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(groupService.setComplianceOverride(groupId, false))
                .assertNext(saved -> assertThat(saved.isComplianceOverride()).isFalse())
                .verifyComplete();
    }

    @Test
    void setComplianceOverrideOfAnUnknownGroupCompletesEmpty() {
        UUID groupId = UUID.randomUUID();
        when(groupRepository.findById(groupId)).thenReturn(Mono.empty());

        StepVerifier.create(groupService.setComplianceOverride(groupId, true)).verifyComplete();

        verify(groupRepository, never()).save(any());
    }

    // --- activeMemberCount / activeMemberParticipantIds (FR-011c's read-shared query, US2/US3) ---

    @Test
    void activeMemberCountCountsOnlyActiveMembers() {
        UUID groupId = UUID.randomUUID();
        stubCount(groupId, 3L);

        StepVerifier.create(groupService.activeMemberCount(groupId))
                .expectNext(3)
                .verifyComplete();
    }

    @Test
    void activeMemberCountIsZeroForAnUnknownOrEmptyGroup() {
        UUID groupId = UUID.randomUUID();
        stubCount(groupId, 0L);

        StepVerifier.create(groupService.activeMemberCount(groupId))
                .expectNext(0)
                .verifyComplete();
    }

    @Test
    void activeMemberParticipantIdsReturnsOnlyActiveMemberIds() {
        UUID groupId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        stubParticipantIds(groupId, List.of(p1, p2));

        StepVerifier.create(groupService.activeMemberParticipantIds(groupId))
                .assertNext(ids -> assertThat(ids).containsExactlyInAnyOrder(p1, p2))
                .verifyComplete();
    }

    @Test
    void activeMemberParticipantIdsIsEmptyForAnUnknownOrEmptyGroup() {
        UUID groupId = UUID.randomUUID();
        stubParticipantIds(groupId, List.of());

        StepVerifier.create(groupService.activeMemberParticipantIds(groupId))
                .assertNext(ids -> assertThat(ids).isEmpty())
                .verifyComplete();
    }

    // --- test helpers ------------------------------------------------------------------------------

    private OrganiserSettings settingsWithMax(int maxGroupMembers) {
        OrganiserSettings settings = new OrganiserSettings();
        settings.setId(UUID.randomUUID());
        settings.setMaxGroupMembers(maxGroupMembers);
        settings.setUpdatedAt(Instant.now());
        return settings;
    }

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
    private void stubCount(UUID groupId, long count) {
        RowsFetchSpec<Long> fetch = mock(RowsFetchSpec.class);
        lenient().when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        lenient().when(executeSpec.bind(eq("gid"), any())).thenReturn(executeSpec);
        lenient().when(executeSpec.mapValue(Long.class)).thenReturn(fetch);
        lenient().when(fetch.one()).thenReturn(Mono.just(count));
    }

    @SuppressWarnings("unchecked")
    private void stubParticipantIds(UUID groupId, List<UUID> ids) {
        RowsFetchSpec<UUID> fetch = mock(RowsFetchSpec.class);
        lenient().when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        lenient().when(executeSpec.bind(eq("gid"), any())).thenReturn(executeSpec);
        lenient().when(executeSpec.mapValue(UUID.class)).thenReturn(fetch);
        lenient().when(fetch.all()).thenReturn(reactor.core.publisher.Flux.fromIterable(ids));
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

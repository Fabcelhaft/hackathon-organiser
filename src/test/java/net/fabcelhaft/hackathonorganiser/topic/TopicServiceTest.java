package net.fabcelhaft.hackathonorganiser.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettings;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.skill.Skill;
import net.fabcelhaft.hackathonorganiser.skill.SkillRepository;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link TopicService} (T044): name/description/creator required, rejecting an
 * unknown {@code created_by_user_id} (FR-015), and Skill association replace (FR-010). Per
 * Constitution Development Workflow #4, the multi-operator reactive chains under test (lookup ->
 * conditional branch -> write) are verified with {@link StepVerifier}, never {@code .block()}.
 */
@ExtendWith(MockitoExtension.class)
class TopicServiceTest {

    @Mock
    private TopicRepository topicRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SkillRepository skillRepository;

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private DatabaseClient.GenericExecuteSpec executeSpec;

    @Mock
    private OrganiserSettingsService organiserSettingsService;

    private TopicService topicService;

    @BeforeEach
    void setUp() {
        topicService = new TopicService(
                topicRepository, userRepository, skillRepository, databaseClient, organiserSettingsService);
    }

    // --- create: name/description/creator required (FR-015) ------------------------------------

    @Test
    void createRejectsMissingName() {
        StepVerifier.create(topicService.create(null, "Description", UUID.randomUUID(), List.of()))
                .expectError(TopicConflictException.class)
                .verify();

        verify(topicRepository, never()).save(any());
    }

    @Test
    void createRejectsBlankName() {
        StepVerifier.create(topicService.create("   ", "Description", UUID.randomUUID(), List.of()))
                .expectError(TopicConflictException.class)
                .verify();

        verify(topicRepository, never()).save(any());
    }

    @Test
    void createRejectsMissingDescription() {
        StepVerifier.create(topicService.create("Name", null, UUID.randomUUID(), List.of()))
                .expectError(TopicConflictException.class)
                .verify();

        verify(topicRepository, never()).save(any());
    }

    @Test
    void createRejectsMissingCreator() {
        StepVerifier.create(topicService.create("Name", "Description", null, List.of()))
                .expectError(TopicConflictException.class)
                .verify();

        verify(topicRepository, never()).save(any());
        verify(userRepository, never()).existsById(any(UUID.class));
    }

    @Test
    void createRejectsAnUnknownCreator() {
        UUID creatorId = UUID.randomUUID();
        when(userRepository.existsById(creatorId)).thenReturn(Mono.just(false));

        StepVerifier.create(topicService.create("Name", "Description", creatorId, List.of()))
                .expectError(TopicConflictException.class)
                .verify();

        verify(topicRepository, never()).save(any());
    }

    @Test
    void createRejectsAnUnknownSkillId() {
        UUID creatorId = UUID.randomUUID();
        UUID unknownSkillId = UUID.randomUUID();
        when(userRepository.existsById(creatorId)).thenReturn(Mono.just(true));
        when(skillRepository.findAllById((Iterable<UUID>) any())).thenReturn(Flux.empty());

        StepVerifier.create(topicService.create("Name", "Description", creatorId, List.of(unknownSkillId)))
                .expectError(TopicConflictException.class)
                .verify();

        verify(topicRepository, never()).save(any());
    }

    @Test
    void createSucceedsWithValidFieldsAndPersistsSkillAssociations() {
        UUID creatorId = UUID.randomUUID();
        UUID skillId = UUID.randomUUID();
        Skill skill = skillOf(skillId);
        when(userRepository.existsById(creatorId)).thenReturn(Mono.just(true));
        when(skillRepository.findAllById((Iterable<UUID>) any())).thenReturn(Flux.just(skill));
        when(topicRepository.save(any(Topic.class)))
                .thenAnswer(invocation -> {
                    Topic topic = invocation.getArgument(0);
                    topic.setId(UUID.randomUUID());
                    return Mono.just(topic);
                });
        stubWriteAlwaysSucceeds();

        StepVerifier.create(topicService.create("Name", "Description", creatorId, List.of(skillId)))
                .assertNext(topic -> {
                    assertThat(topic.getName()).isEqualTo("Name");
                    assertThat(topic.getDescription()).isEqualTo("Description");
                    assertThat(topic.getCreatedByUserId()).isEqualTo(creatorId);
                    assertThat(topic.getCreatedAt()).isNotNull();
                    assertThat(topic.getUpdatedAt()).isNotNull();
                })
                .verifyComplete();
    }

    // --- update: creator is immutable, name/description still required, Skill replace (FR-010) -

    @Test
    void updateRejectsMissingName() {
        StepVerifier.create(topicService.update(UUID.randomUUID(), null, "Description", List.of()))
                .expectError(TopicConflictException.class)
                .verify();

        verify(topicRepository, never()).findById(any(UUID.class));
    }

    @Test
    void updateRejectsMissingDescription() {
        StepVerifier.create(topicService.update(UUID.randomUUID(), "Name", null, List.of()))
                .expectError(TopicConflictException.class)
                .verify();

        verify(topicRepository, never()).findById(any(UUID.class));
    }

    @Test
    void updateOfAnUnknownTopicCompletesEmpty() {
        UUID id = UUID.randomUUID();
        when(topicRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(topicService.update(id, "Name", "Description", List.of()))
                .verifyComplete();

        verify(topicRepository, never()).save(any());
    }

    @Test
    void updateRejectsAnUnknownSkillId() {
        UUID id = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID unknownSkillId = UUID.randomUUID();
        Topic existing = topicOf(id, "Old Name", "Old Description", creatorId);
        when(topicRepository.findById(id)).thenReturn(Mono.just(existing));
        when(skillRepository.findAllById((Iterable<UUID>) any())).thenReturn(Flux.empty());

        StepVerifier.create(topicService.update(id, "New Name", "New Description", List.of(unknownSkillId)))
                .expectError(TopicConflictException.class)
                .verify();

        verify(topicRepository, never()).save(any());
    }

    @Test
    void updateReplacesNameAndDescriptionWithoutTouchingTheCreator() {
        UUID id = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID skillId = UUID.randomUUID();
        Skill skill = skillOf(skillId);
        Topic existing = topicOf(id, "Old Name", "Old Description", creatorId);
        when(topicRepository.findById(id)).thenReturn(Mono.just(existing));
        when(skillRepository.findAllById((Iterable<UUID>) any())).thenReturn(Flux.just(skill));
        when(topicRepository.save(any(Topic.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        stubWriteAlwaysSucceeds();

        StepVerifier.create(topicService.update(id, "New Name", "New Description", List.of(skillId)))
                .assertNext(topic -> {
                    assertThat(topic.getName()).isEqualTo("New Name");
                    assertThat(topic.getDescription()).isEqualTo("New Description");
                    // FR-015: the creator is immutable after creation — update(...) takes no
                    // parameter for it at all, so it is structurally impossible for this call to
                    // have changed it.
                    assertThat(topic.getCreatedByUserId()).isEqualTo(creatorId);
                })
                .verifyComplete();
    }

    // --- propose: approval_status from the current setting (FR-013), never retroactive (FR-016) --

    @Test
    void proposeRejectsMissingName() {
        StepVerifier.create(topicService.propose(UUID.randomUUID(), null, "Description"))
                .expectError(TopicConflictException.class)
                .verify();

        verify(topicRepository, never()).save(any());
    }

    @Test
    void proposeRejectsMissingDescription() {
        StepVerifier.create(topicService.propose(UUID.randomUUID(), "Name", null))
                .expectError(TopicConflictException.class)
                .verify();

        verify(topicRepository, never()).save(any());
    }

    @Test
    void proposeStartsPendingWhenTopicApprovalIsRequired() {
        UUID authorId = UUID.randomUUID();
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true)));
        when(topicRepository.save(any(Topic.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(topicService.propose(authorId, "Name", "Description"))
                .assertNext(topic -> {
                    assertThat(topic.getApprovalStatus()).isEqualTo(TopicApprovalStatus.PENDING);
                    assertThat(topic.getCreatedByUserId()).isEqualTo(authorId);
                })
                .verifyComplete();
    }

    @Test
    void proposeStartsApprovedWhenTopicApprovalIsNotRequired() {
        UUID authorId = UUID.randomUUID();
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(false)));
        when(topicRepository.save(any(Topic.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(topicService.propose(authorId, "Name", "Description"))
                .assertNext(topic -> assertThat(topic.getApprovalStatus()).isEqualTo(TopicApprovalStatus.APPROVED))
                .verifyComplete();
    }

    // --- findVisibleTopicsFor: viewer-scoped 3-group visibility/ordering (FR-009a, FR-012a) ------

    @Test
    void findVisibleTopicsForGroupsOwnPendingOwnApprovedAndOthersEachByCreationDate() {
        UUID viewerId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Instant t0 = Instant.now().minusSeconds(300);
        Topic ownPendingOld = approvalTopicOf("Own Pending Old", viewerId, TopicApprovalStatus.PENDING, t0);
        Topic ownPendingNew =
                approvalTopicOf("Own Pending New", viewerId, TopicApprovalStatus.PENDING, t0.plusSeconds(10));
        Topic ownApproved = approvalTopicOf("Own Approved", viewerId, TopicApprovalStatus.APPROVED, t0);
        Topic otherApprovedOld =
                approvalTopicOf("Other Approved Old", otherId, TopicApprovalStatus.APPROVED, t0.plusSeconds(5));
        Topic otherApprovedNew =
                approvalTopicOf("Other Approved New", otherId, TopicApprovalStatus.APPROVED, t0.plusSeconds(20));
        Topic otherPending = approvalTopicOf("Other Pending", otherId, TopicApprovalStatus.PENDING, t0);
        when(topicRepository.findAll())
                .thenReturn(Flux.just(
                        ownPendingNew,
                        ownPendingOld,
                        ownApproved,
                        otherApprovedNew,
                        otherApprovedOld,
                        otherPending));

        StepVerifier.create(topicService.findVisibleTopicsFor(viewerId, false))
                .assertNext(view -> {
                    assertThat(view.ownPending()).containsExactly(ownPendingOld, ownPendingNew);
                    assertThat(view.ownApproved()).containsExactly(ownApproved);
                    // otherPending is PENDING and not the viewer's own: invisible to a non-Organiser
                    // viewer (FR-012a), so it must not appear in any group.
                    assertThat(view.others()).containsExactly(otherApprovedOld, otherApprovedNew);
                })
                .verifyComplete();
    }

    @Test
    void findVisibleTopicsForIncludesOthersPendingTopicsInTheOthersGroupForAnOrganiserViewer() {
        UUID viewerId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Instant t0 = Instant.now().minusSeconds(60);
        Topic otherApproved = approvalTopicOf("Other Approved", otherId, TopicApprovalStatus.APPROVED, t0);
        Topic otherPending = approvalTopicOf("Other Pending", otherId, TopicApprovalStatus.PENDING, t0.plusSeconds(5));
        when(topicRepository.findAll()).thenReturn(Flux.just(otherApproved, otherPending));

        StepVerifier.create(topicService.findVisibleTopicsFor(viewerId, true))
                .assertNext(view -> {
                    assertThat(view.ownPending()).isEmpty();
                    assertThat(view.ownApproved()).isEmpty();
                    assertThat(view.others()).containsExactly(otherApproved, otherPending);
                })
                .verifyComplete();
    }

    // --- test helpers ------------------------------------------------------------------------------

    private OrganiserSettings settingsOf(boolean topicApprovalRequired) {
        OrganiserSettings settings = new OrganiserSettings();
        settings.setId(UUID.randomUUID());
        settings.setSingleton(true);
        settings.setSelfRegistrationEnabled(true);
        settings.setSelfRevocationEnabled(true);
        settings.setTopicApprovalRequired(topicApprovalRequired);
        settings.setUpdatedAt(Instant.now());
        return settings;
    }

    private Topic approvalTopicOf(
            String name, UUID createdByUserId, TopicApprovalStatus approvalStatus, Instant createdAt) {
        Topic topic = new Topic();
        topic.setId(UUID.randomUUID());
        topic.setName(name);
        topic.setDescription("Description");
        topic.setCreatedByUserId(createdByUserId);
        topic.setApprovalStatus(approvalStatus);
        topic.setCreatedAt(createdAt);
        topic.setUpdatedAt(createdAt);
        return topic;
    }

    private Topic topicOf(UUID id, String name, String description, UUID createdByUserId) {
        Topic topic = new Topic();
        topic.setId(id);
        topic.setName(name);
        topic.setDescription(description);
        topic.setCreatedByUserId(createdByUserId);
        topic.setCreatedAt(Instant.now());
        topic.setUpdatedAt(Instant.now());
        return topic;
    }

    private Skill skillOf(UUID id) {
        Skill skill = new Skill();
        skill.setId(id);
        skill.setName("Skill " + id);
        skill.setCreatedAt(Instant.now());
        skill.setUpdatedAt(Instant.now());
        return skill;
    }

    private void stubWriteAlwaysSucceeds() {
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());
    }
}

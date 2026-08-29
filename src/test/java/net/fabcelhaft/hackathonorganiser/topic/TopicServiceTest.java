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

    private TopicService topicService;

    @BeforeEach
    void setUp() {
        topicService = new TopicService(topicRepository, userRepository, skillRepository, databaseClient);
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

    // --- test helpers ------------------------------------------------------------------------------

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

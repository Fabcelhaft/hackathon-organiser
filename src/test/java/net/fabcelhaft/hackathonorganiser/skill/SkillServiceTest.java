package net.fabcelhaft.hackathonorganiser.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.BadSqlGrammarException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link SkillService} (T020): case-insensitive create/rename uniqueness (FR-008a)
 * and the referential delete-guard (FR-023). Per Constitution Development Workflow #4, the
 * multi-operator reactive chains under test (lookup -> conditional branch -> write) are verified
 * with {@link StepVerifier}, never {@code .block()}.
 */
@ExtendWith(MockitoExtension.class)
class SkillServiceTest {

    @Mock
    private SkillRepository skillRepository;

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private DatabaseClient.GenericExecuteSpec executeSpec;

    private SkillService skillService;

    @BeforeEach
    void setUp() {
        skillService = new SkillService(skillRepository, databaseClient);
    }

    // --- create: case-insensitive uniqueness (FR-008a) -----------------------------------------

    @Test
    void createSucceedsWhenNameIsNotADuplicate() {
        when(skillRepository.existsByNameIgnoreCase("Java")).thenReturn(Mono.just(false));
        when(skillRepository.save(any(Skill.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(skillService.create("Java"))
                .assertNext(skill -> {
                    assertThat(skill.getName()).isEqualTo("Java");
                    assertThat(skill.getCreatedAt()).isNotNull();
                    assertThat(skill.getUpdatedAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void createRejectsCaseInsensitiveDuplicateName() {
        when(skillRepository.existsByNameIgnoreCase("java")).thenReturn(Mono.just(true));

        StepVerifier.create(skillService.create("java"))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(SkillConflictException.class);
                    assertThat(ex.getMessage()).contains("java");
                })
                .verify();

        verify(skillRepository, never()).save(any());
    }

    // --- rename: case-insensitive uniqueness excluding self (FR-008a) --------------------------

    @Test
    void renameSucceedsWhenNoConflict() {
        UUID id = UUID.randomUUID();
        Skill existing = skillOf(id, "Old Name");
        when(skillRepository.findById(id)).thenReturn(Mono.just(existing));
        when(skillRepository.existsByNameIgnoreCaseAndIdNot("New Name", id)).thenReturn(Mono.just(false));
        when(skillRepository.save(any(Skill.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(skillService.rename(id, "New Name"))
                .assertNext(skill -> assertThat(skill.getName()).isEqualTo("New Name"))
                .verifyComplete();
    }

    @Test
    void renameRejectsCaseInsensitiveDuplicateAgainstAnotherSkill() {
        UUID id = UUID.randomUUID();
        Skill existing = skillOf(id, "Old Name");
        when(skillRepository.findById(id)).thenReturn(Mono.just(existing));
        when(skillRepository.existsByNameIgnoreCaseAndIdNot("python", id)).thenReturn(Mono.just(true));

        StepVerifier.create(skillService.rename(id, "python"))
                .expectError(SkillConflictException.class)
                .verify();

        verify(skillRepository, never()).save(any());
    }

    @Test
    void renameOfUnknownSkillCompletesEmpty() {
        UUID id = UUID.randomUUID();
        when(skillRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(skillService.rename(id, "Whatever")).verifyComplete();
    }

    // --- delete: referential guard (FR-023) -----------------------------------------------------

    @Test
    void deleteSucceedsWhenNotReferenced() {
        UUID id = UUID.randomUUID();
        stubReferenceCounts(0L, 0L);
        when(skillRepository.deleteById(id)).thenReturn(Mono.empty());

        StepVerifier.create(skillService.delete(id)).verifyComplete();

        verify(skillRepository).deleteById(id);
    }

    @Test
    void deleteIsBlockedWhileReferenced() {
        UUID id = UUID.randomUUID();
        stubReferenceCounts(2L, 0L);

        StepVerifier.create(skillService.delete(id))
                .expectError(SkillConflictException.class)
                .verify();

        verify(skillRepository, never()).deleteById(any(UUID.class));
    }

    @Test
    void deleteTreatsMissingReferenceTablesAsNotYetReferenced() {
        // Simulates participant_skills/topic_skills not existing yet in this story's schema
        // (they are added by User Stories 3 and 4): the guard query's BadSqlGrammarException is
        // treated defensively as "zero references" so this story's delete flow still works.
        UUID id = UUID.randomUUID();
        RowsFetchSpec<Long> failingFetch = mockFetch();
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(eq("id"), any())).thenReturn(executeSpec);
        when(executeSpec.mapValue(Long.class)).thenReturn(failingFetch);
        when(failingFetch.one()).thenReturn(Mono.error(new BadSqlGrammarException("count", "sql", null)));
        when(skillRepository.deleteById(id)).thenReturn(Mono.empty());

        StepVerifier.create(skillService.delete(id)).verifyComplete();

        verify(skillRepository).deleteById(id);
    }

    // --- test helpers ----------------------------------------------------------------------------

    private Skill skillOf(UUID id, String name) {
        Skill skill = new Skill();
        skill.setId(id);
        skill.setName(name);
        skill.setCreatedAt(Instant.now());
        skill.setUpdatedAt(Instant.now());
        return skill;
    }

    @SuppressWarnings("unchecked")
    private RowsFetchSpec<Long> mockFetch() {
        return mock(RowsFetchSpec.class);
    }

    private void stubReferenceCounts(long participantSkillsCount, long topicSkillsCount) {
        RowsFetchSpec<Long> firstFetch = mockFetch();
        RowsFetchSpec<Long> secondFetch = mockFetch();
        when(firstFetch.one()).thenReturn(Mono.just(participantSkillsCount));
        when(secondFetch.one()).thenReturn(Mono.just(topicSkillsCount));

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(eq("id"), any())).thenReturn(executeSpec);
        when(executeSpec.mapValue(Long.class)).thenReturn(firstFetch, secondFetch);
    }
}

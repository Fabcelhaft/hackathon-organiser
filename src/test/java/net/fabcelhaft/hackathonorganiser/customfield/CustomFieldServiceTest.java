package net.fabcelhaft.hackathonorganiser.customfield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.BadSqlGrammarException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link CustomFieldService} (T021): the {@code MULTI_SELECT} minimum-option rule
 * (FR-012), the {@code field_type} lock once a value exists (FR-012a), the option delete-guard
 * (FR-012b), and the definition delete-guard (FR-023). Per Constitution Development Workflow #4,
 * the multi-operator reactive chains under test are verified with {@link StepVerifier}, never
 * {@code .block()}.
 */
@ExtendWith(MockitoExtension.class)
class CustomFieldServiceTest {

    @Mock
    private CustomFieldDefinitionRepository definitionRepository;

    @Mock
    private CustomFieldOptionRepository optionRepository;

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private DatabaseClient.GenericExecuteSpec executeSpec;

    private CustomFieldService customFieldService;

    @BeforeEach
    void setUp() {
        customFieldService = new CustomFieldService(definitionRepository, optionRepository, databaseClient);
    }

    // --- create: MULTI_SELECT requires at least one option (FR-012) ----------------------------

    @Test
    void createRejectsMultiSelectWithNoOptions() {
        StepVerifier.create(customFieldService.create("Languages", CustomFieldType.MULTI_SELECT, false, List.of()))
                .expectError(CustomFieldConflictException.class)
                .verify();

        verify(definitionRepository, never()).save(any());
    }

    @Test
    void createFreeTextSucceedsWithoutOptions() {
        when(definitionRepository.save(any(CustomFieldDefinition.class)))
                .thenAnswer(invocation -> Mono.just(withId(invocation.<CustomFieldDefinition>getArgument(0))));

        StepVerifier.create(customFieldService.create("T-Shirt Size", CustomFieldType.FREE_TEXT, true, null))
                .assertNext(definition -> {
                    assertThat(definition.getLabel()).isEqualTo("T-Shirt Size");
                    assertThat(definition.getFieldType()).isEqualTo(CustomFieldType.FREE_TEXT);
                    assertThat(definition.isRequired()).isTrue();
                })
                .verifyComplete();

        verify(optionRepository, never()).save(any());
    }

    @Test
    void createMultiSelectSucceedsAndPersistsEachOption() {
        when(definitionRepository.save(any(CustomFieldDefinition.class)))
                .thenAnswer(invocation -> Mono.just(withId(invocation.<CustomFieldDefinition>getArgument(0))));
        when(optionRepository.save(any(CustomFieldOption.class)))
                .thenAnswer(invocation -> Mono.just(withId(invocation.<CustomFieldOption>getArgument(0))));

        StepVerifier.create(customFieldService.create(
                        "Languages", CustomFieldType.MULTI_SELECT, false, List.of("Java", "Python")))
                .assertNext(definition -> assertThat(definition.getFieldType()).isEqualTo(CustomFieldType.MULTI_SELECT))
                .verifyComplete();

        verify(optionRepository, times(2)).save(any(CustomFieldOption.class));
    }

    // --- update: field_type lock once a value exists (FR-012a) ---------------------------------

    @Test
    void typeChangeIsRejectedOnceAParticipantValueExists() {
        UUID id = UUID.randomUUID();
        CustomFieldDefinition existing = definitionOf(id, "Languages", CustomFieldType.FREE_TEXT, false);
        when(definitionRepository.findById(id)).thenReturn(Mono.just(existing));
        stubValueReferenceCounts(1L, 0L);

        StepVerifier.create(customFieldService.update(id, "Languages", false, CustomFieldType.MULTI_SELECT))
                .expectError(CustomFieldConflictException.class)
                .verify();

        verify(definitionRepository, never()).save(any());
    }

    @Test
    void typeChangeIsAllowedWhenNoValueExists() {
        UUID id = UUID.randomUUID();
        CustomFieldDefinition existing = definitionOf(id, "Languages", CustomFieldType.FREE_TEXT, false);
        when(definitionRepository.findById(id)).thenReturn(Mono.just(existing));
        stubValueReferenceCounts(0L, 0L);
        when(definitionRepository.save(any(CustomFieldDefinition.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(customFieldService.update(id, "Languages", false, CustomFieldType.MULTI_SELECT))
                .assertNext(definition -> assertThat(definition.getFieldType()).isEqualTo(CustomFieldType.MULTI_SELECT))
                .verifyComplete();
    }

    @Test
    void updateWithoutRequestingATypeChangeNeverChecksTheGuard() {
        UUID id = UUID.randomUUID();
        CustomFieldDefinition existing = definitionOf(id, "Languages", CustomFieldType.FREE_TEXT, false);
        when(definitionRepository.findById(id)).thenReturn(Mono.just(existing));
        when(definitionRepository.save(any(CustomFieldDefinition.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(customFieldService.update(id, "Renamed", true, CustomFieldType.FREE_TEXT))
                .assertNext(definition -> {
                    assertThat(definition.getLabel()).isEqualTo("Renamed");
                    assertThat(definition.isRequired()).isTrue();
                })
                .verifyComplete();

        verify(databaseClient, never()).sql(anyString());
    }

    // --- delete option: referential guard (FR-012b) ---------------------------------------------

    @Test
    void optionRemovalIsBlockedWhileReferenced() {
        UUID optionId = UUID.randomUUID();
        RowsFetchSpec<Long> fetch = mockFetch();
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(eq("id"), any())).thenReturn(executeSpec);
        when(executeSpec.mapValue(Long.class)).thenReturn(fetch);
        when(fetch.one()).thenReturn(Mono.just(3L));

        StepVerifier.create(customFieldService.deleteOption(optionId))
                .expectError(CustomFieldConflictException.class)
                .verify();

        verify(optionRepository, never()).deleteById(any(UUID.class));
    }

    @Test
    void optionRemovalSucceedsWhenNotReferenced() {
        UUID optionId = UUID.randomUUID();
        RowsFetchSpec<Long> fetch = mockFetch();
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(eq("id"), any())).thenReturn(executeSpec);
        when(executeSpec.mapValue(Long.class)).thenReturn(fetch);
        when(fetch.one()).thenReturn(Mono.just(0L));
        when(optionRepository.deleteById(optionId)).thenReturn(Mono.empty());

        StepVerifier.create(customFieldService.deleteOption(optionId)).verifyComplete();

        verify(optionRepository).deleteById(optionId);
    }

    // --- delete definition: referential guard (FR-023) ------------------------------------------

    @Test
    void definitionRemovalIsBlockedWhileReferenced() {
        UUID id = UUID.randomUUID();
        stubValueReferenceCounts(4L, 0L);

        StepVerifier.create(customFieldService.deleteDefinition(id))
                .expectError(CustomFieldConflictException.class)
                .verify();

        verify(definitionRepository, never()).deleteById(any(UUID.class));
    }

    @Test
    void definitionRemovalSucceedsWhenNotReferencedAndCascadesItsOptions() {
        UUID id = UUID.randomUUID();
        stubValueReferenceCounts(0L, 0L);
        CustomFieldOption option = new CustomFieldOption();
        option.setId(UUID.randomUUID());
        option.setCustomFieldDefinitionId(id);
        when(optionRepository.findByCustomFieldDefinitionId(id)).thenReturn(Flux.just(option));
        when(optionRepository.deleteById(option.getId())).thenReturn(Mono.empty());
        when(definitionRepository.deleteById(id)).thenReturn(Mono.empty());

        StepVerifier.create(customFieldService.deleteDefinition(id)).verifyComplete();

        verify(optionRepository).deleteById(option.getId());
        verify(definitionRepository).deleteById(id);
    }

    @Test
    void deleteTreatsMissingValueTablesAsNotYetReferenced() {
        // Simulates custom_field_values/custom_field_value_options not existing yet in this
        // story's schema (they are added by User Story 3): the guard query's
        // BadSqlGrammarException is treated defensively as "zero references".
        UUID id = UUID.randomUUID();
        RowsFetchSpec<Long> failingFetch = mockFetch();
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(eq("id"), any())).thenReturn(executeSpec);
        when(executeSpec.mapValue(Long.class)).thenReturn(failingFetch);
        when(failingFetch.one()).thenReturn(Mono.error(new BadSqlGrammarException("count", "sql", null)));
        when(optionRepository.findByCustomFieldDefinitionId(id)).thenReturn(Flux.empty());
        when(definitionRepository.deleteById(id)).thenReturn(Mono.empty());

        StepVerifier.create(customFieldService.deleteDefinition(id)).verifyComplete();

        verify(definitionRepository).deleteById(id);
    }

    // --- add option: duplicate label rejected case-insensitively --------------------------------

    @Test
    void addOptionRejectsCaseInsensitiveDuplicateLabel() {
        UUID definitionId = UUID.randomUUID();
        CustomFieldDefinition definition =
                definitionOf(definitionId, "Languages", CustomFieldType.MULTI_SELECT, false);
        when(definitionRepository.findById(definitionId)).thenReturn(Mono.just(definition));
        when(optionRepository.existsByCustomFieldDefinitionIdAndLabelIgnoreCase(definitionId, "java"))
                .thenReturn(Mono.just(true));

        StepVerifier.create(customFieldService.addOption(definitionId, "java"))
                .expectError(CustomFieldConflictException.class)
                .verify();

        verify(optionRepository, never()).save(any());
    }

    // --- test helpers ----------------------------------------------------------------------------

    private CustomFieldDefinition definitionOf(UUID id, String label, CustomFieldType type, boolean required) {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setId(id);
        definition.setLabel(label);
        definition.setFieldType(type);
        definition.setRequired(required);
        definition.setCreatedAt(Instant.now());
        definition.setUpdatedAt(Instant.now());
        return definition;
    }

    private CustomFieldDefinition withId(CustomFieldDefinition definition) {
        if (definition.getId() == null) {
            definition.setId(UUID.randomUUID());
        }
        return definition;
    }

    private CustomFieldOption withId(CustomFieldOption option) {
        if (option.getId() == null) {
            option.setId(UUID.randomUUID());
        }
        return option;
    }

    @SuppressWarnings("unchecked")
    private RowsFetchSpec<Long> mockFetch() {
        return mock(RowsFetchSpec.class);
    }

    private void stubValueReferenceCounts(long valuesCount, long valueOptionsCount) {
        RowsFetchSpec<Long> firstFetch = mockFetch();
        RowsFetchSpec<Long> secondFetch = mockFetch();
        when(firstFetch.one()).thenReturn(Mono.just(valuesCount));
        when(secondFetch.one()).thenReturn(Mono.just(valueOptionsCount));

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(eq("id"), any())).thenReturn(executeSpec);
        when(executeSpec.mapValue(Long.class)).thenReturn(firstFetch, secondFetch);
    }
}

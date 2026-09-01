package net.fabcelhaft.hackathonorganiser.compliance;

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
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinition;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinitionRepository;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldType;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettings;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link ComplianceService#evaluate} (T017; FR-012, FR-012a, FR-014, research.md
 * §1/§5): {@code COMPLIANT_OVERRIDE} short-circuits every other check; otherwise {@code COMPLIANT}
 * iff the member count is within the configured Maximum (inclusive) and Minimum (when set), and
 * every configured diversity requirement has enough distinct non-blank values. Per Constitution
 * Development Workflow #4, the multi-operator reactive chains under test are verified with
 * {@link StepVerifier}, never {@code .block()}.
 */
@ExtendWith(MockitoExtension.class)
class ComplianceServiceTest {

    @Mock
    private OrganiserSettingsService organiserSettingsService;

    @Mock
    private ComplianceDiversityRequirementRepository requirementRepository;

    @Mock
    private CustomFieldDefinitionRepository customFieldDefinitionRepository;

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private DatabaseClient.GenericExecuteSpec executeSpec;

    private ComplianceService complianceService;

    @BeforeEach
    void setUp() {
        complianceService = new ComplianceService(
                organiserSettingsService, requirementRepository, customFieldDefinitionRepository, databaseClient);
    }

    // --- COMPLIANT_OVERRIDE short-circuits everything else (FR-015) ------------------------------

    @Test
    void evaluateReturnsCompliantOverrideAndShortCircuitsEveryOtherCheck() {
        Group group = groupWithOverride(true);

        StepVerifier.create(complianceService.evaluate(group, List.of(UUID.randomUUID())))
                .expectNext(ComplianceStatus.COMPLIANT_OVERRIDE)
                .verifyComplete();

        verify(organiserSettingsService, never()).current();
        verify(requirementRepository, never()).findAll();
    }

    // --- Maximum/Minimum (inclusive Maximum reading, research.md §1) -----------------------------

    @Test
    void evaluateIsCompliantWithNoOptionalRulesConfiguredWhenAtOrBelowMaximum() {
        Group group = groupWithOverride(false);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, null)));
        when(requirementRepository.findAll()).thenReturn(Flux.empty());

        StepVerifier.create(complianceService.evaluate(group, threeParticipantIds()))
                .expectNext(ComplianceStatus.COMPLIANT)
                .verifyComplete();
    }

    @Test
    void evaluateIsCompliantWhenMemberCountIsExactlyAtTheMaximum() {
        Group group = groupWithOverride(false);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(3, null)));
        when(requirementRepository.findAll()).thenReturn(Flux.empty());

        StepVerifier.create(complianceService.evaluate(group, threeParticipantIds()))
                .expectNext(ComplianceStatus.COMPLIANT)
                .verifyComplete();
    }

    @Test
    void evaluateIsNotCompliantWhenAboveTheMaximum() {
        Group group = groupWithOverride(false);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(2, null)));
        lenient().when(requirementRepository.findAll()).thenReturn(Flux.empty());

        StepVerifier.create(complianceService.evaluate(group, threeParticipantIds()))
                .expectNext(ComplianceStatus.NOT_COMPLIANT)
                .verifyComplete();
    }

    @Test
    void evaluateIsNotCompliantWhenBelowTheConfiguredMinimum() {
        Group group = groupWithOverride(false);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, 3)));

        StepVerifier.create(complianceService.evaluate(group, twoParticipantIds()))
                .expectNext(ComplianceStatus.NOT_COMPLIANT)
                .verifyComplete();
    }

    // --- Diversity requirements, AND-combined (FR-012a) -------------------------------------------

    @Test
    void evaluateIsCompliantWhenADiversityRequirementHasEnoughDistinctValues() {
        Group group = groupWithOverride(false);
        UUID fieldId = UUID.randomUUID();
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, null)));
        when(requirementRepository.findAll()).thenReturn(Flux.just(requirementOf(fieldId, 2)));
        when(customFieldDefinitionRepository.findById(fieldId))
                .thenReturn(Mono.just(definitionOf(fieldId, CustomFieldType.FREE_TEXT)));
        stubDistinctCount(2L);

        StepVerifier.create(complianceService.evaluate(group, twoParticipantIds()))
                .expectNext(ComplianceStatus.COMPLIANT)
                .verifyComplete();
    }

    @Test
    void evaluateIsNotCompliantWhenADiversityRequirementHasTooFewDistinctValues() {
        Group group = groupWithOverride(false);
        UUID fieldId = UUID.randomUUID();
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, null)));
        when(requirementRepository.findAll()).thenReturn(Flux.just(requirementOf(fieldId, 2)));
        when(customFieldDefinitionRepository.findById(fieldId))
                .thenReturn(Mono.just(definitionOf(fieldId, CustomFieldType.FREE_TEXT)));
        // Simulates two members sharing the same value, or one leaving it blank — either way the
        // query-level blank/duplicate filtering (schema.sql) reports fewer distinct values than
        // configured members, so the requirement is not satisfied.
        stubDistinctCount(1L);

        StepVerifier.create(complianceService.evaluate(group, twoParticipantIds()))
                .expectNext(ComplianceStatus.NOT_COMPLIANT)
                .verifyComplete();
    }

    @Test
    void evaluateIsNotCompliantWhenTooManyMemberValuesAreBlank() {
        Group group = groupWithOverride(false);
        UUID fieldId = UUID.randomUUID();
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, null)));
        when(requirementRepository.findAll()).thenReturn(Flux.just(requirementOf(fieldId, 2)));
        when(customFieldDefinitionRepository.findById(fieldId))
                .thenReturn(Mono.just(definitionOf(fieldId, CustomFieldType.FREE_TEXT)));
        // Only one of the two members recorded a non-blank value.
        stubDistinctCount(1L);

        StepVerifier.create(complianceService.evaluate(group, twoParticipantIds()))
                .expectNext(ComplianceStatus.NOT_COMPLIANT)
                .verifyComplete();
    }

    @Test
    void evaluateIsCompliantForASelectTypeDiversityRequirementWithEnoughDistinctOptions() {
        Group group = groupWithOverride(false);
        UUID fieldId = UUID.randomUUID();
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, null)));
        when(requirementRepository.findAll()).thenReturn(Flux.just(requirementOf(fieldId, 2)));
        when(customFieldDefinitionRepository.findById(fieldId))
                .thenReturn(Mono.just(definitionOf(fieldId, CustomFieldType.SINGLE_SELECT)));
        stubDistinctCount(2L);

        StepVerifier.create(complianceService.evaluate(group, twoParticipantIds()))
                .expectNext(ComplianceStatus.COMPLIANT)
                .verifyComplete();
    }

    // --- addRequirement / removeRequirement (FR-011, FR-011d, US6) --------------------------------

    @Test
    void addRequirementRejectsAMinimumBelowTwo() {
        StepVerifier.create(complianceService.addRequirement(UUID.randomUUID(), 1))
                .expectError(ComplianceConflictException.class)
                .verify();

        verify(customFieldDefinitionRepository, never()).existsById(any(UUID.class));
    }

    @Test
    void addRequirementRejectsAMissingCustomFieldId() {
        StepVerifier.create(complianceService.addRequirement(null, 2))
                .expectError(ComplianceConflictException.class)
                .verify();

        verify(customFieldDefinitionRepository, never()).existsById(any(UUID.class));
    }

    @Test
    void addRequirementRejectsAnUnknownCustomFieldId() {
        UUID fieldId = UUID.randomUUID();
        when(customFieldDefinitionRepository.existsById(fieldId)).thenReturn(Mono.just(false));

        StepVerifier.create(complianceService.addRequirement(fieldId, 2))
                .expectError(ComplianceConflictException.class)
                .verify();

        verify(requirementRepository, never()).save(any());
    }

    @Test
    void addRequirementRejectsAFieldAlreadyConfigured() {
        UUID fieldId = UUID.randomUUID();
        when(customFieldDefinitionRepository.existsById(fieldId)).thenReturn(Mono.just(true));
        when(requirementRepository.existsByCustomFieldDefinitionId(fieldId)).thenReturn(Mono.just(true));

        StepVerifier.create(complianceService.addRequirement(fieldId, 2))
                .expectError(ComplianceConflictException.class)
                .verify();

        verify(requirementRepository, never()).save(any());
    }

    @Test
    void addRequirementSucceedsForAValidUnconfiguredField() {
        UUID fieldId = UUID.randomUUID();
        when(customFieldDefinitionRepository.existsById(fieldId)).thenReturn(Mono.just(true));
        when(requirementRepository.existsByCustomFieldDefinitionId(fieldId)).thenReturn(Mono.just(false));
        when(requirementRepository.save(any(ComplianceDiversityRequirement.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(complianceService.addRequirement(fieldId, 3))
                .assertNext(saved -> {
                    org.assertj.core.api.Assertions.assertThat(saved.getCustomFieldDefinitionId())
                            .isEqualTo(fieldId);
                    org.assertj.core.api.Assertions.assertThat(saved.getMinimumDistinctValues())
                            .isEqualTo(3);
                })
                .verifyComplete();
    }

    @Test
    void removeRequirementAlwaysSucceedsForAnExistingRow() {
        UUID id = UUID.randomUUID();
        when(requirementRepository.deleteById(id)).thenReturn(Mono.empty());

        StepVerifier.create(complianceService.removeRequirement(id)).verifyComplete();

        verify(requirementRepository).deleteById(id);
    }

    // --- test helpers ------------------------------------------------------------------------------

    private Group groupWithOverride(boolean override) {
        Group group = new Group();
        group.setId(UUID.randomUUID());
        group.setComplianceOverride(override);
        return group;
    }

    private OrganiserSettings settingsOf(int maxGroupMembers, Integer minGroupMembers) {
        OrganiserSettings settings = new OrganiserSettings();
        settings.setId(UUID.randomUUID());
        settings.setMaxGroupMembers(maxGroupMembers);
        settings.setMinGroupMembers(minGroupMembers);
        settings.setUpdatedAt(Instant.now());
        return settings;
    }

    private ComplianceDiversityRequirement requirementOf(UUID fieldId, int minimumDistinctValues) {
        ComplianceDiversityRequirement requirement = new ComplianceDiversityRequirement();
        requirement.setId(UUID.randomUUID());
        requirement.setCustomFieldDefinitionId(fieldId);
        requirement.setMinimumDistinctValues(minimumDistinctValues);
        requirement.setCreatedAt(Instant.now());
        requirement.setUpdatedAt(Instant.now());
        return requirement;
    }

    private CustomFieldDefinition definitionOf(UUID id, CustomFieldType fieldType) {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setId(id);
        definition.setLabel("Country");
        definition.setFieldType(fieldType);
        definition.setCreatedAt(Instant.now());
        definition.setUpdatedAt(Instant.now());
        return definition;
    }

    private List<UUID> twoParticipantIds() {
        return List.of(UUID.randomUUID(), UUID.randomUUID());
    }

    private List<UUID> threeParticipantIds() {
        return List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    @SuppressWarnings("unchecked")
    private void stubDistinctCount(long count) {
        RowsFetchSpec<Long> fetch = mock(RowsFetchSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(eq("fid"), any())).thenReturn(executeSpec);
        when(executeSpec.bind(eq("pids"), any())).thenReturn(executeSpec);
        when(executeSpec.mapValue(Long.class)).thenReturn(fetch);
        when(fetch.one()).thenReturn(Mono.just(count));
    }
}

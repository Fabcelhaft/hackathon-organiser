package net.fabcelhaft.hackathonorganiser.participant;

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
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinition;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinitionRepository;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldOption;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldOptionRepository;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldType;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettings;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.skill.SkillRepository;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
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
 * Unit tests for {@link ParticipantService} (T035): single Participant per User (FR-006a),
 * initial status {@code ACTIVE} (FR-006b), status restricted to the three-value enum (FR-007),
 * Custom Field value validated against the field's configured type (FR-014), and the incomplete
 * computation (FR-027). Per Constitution Development Workflow #4, the multi-operator reactive
 * chains under test (lookup -> conditional branch -> write) are verified with
 * {@link StepVerifier}, never {@code .block()}.
 */
@ExtendWith(MockitoExtension.class)
class ParticipantServiceTest {

    @Mock
    private ParticipantRepository participantRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SkillRepository skillRepository;

    @Mock
    private CustomFieldDefinitionRepository customFieldDefinitionRepository;

    @Mock
    private CustomFieldOptionRepository customFieldOptionRepository;

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private DatabaseClient.GenericExecuteSpec executeSpec;

    @Mock
    private OrganiserSettingsService organiserSettingsService;

    @Mock
    private GroupService groupService;

    private ParticipantService participantService;

    @BeforeEach
    void setUp() {
        participantService = new ParticipantService(
                participantRepository,
                userRepository,
                skillRepository,
                customFieldDefinitionRepository,
                customFieldOptionRepository,
                databaseClient,
                organiserSettingsService,
                groupService);
    }

    // --- register: single Participant per User (FR-006a), initial status ACTIVE (FR-006b) ------

    @Test
    void registerSucceedsWithInitialStatusActive() {
        UUID userId = UUID.randomUUID();
        when(userRepository.existsById(userId)).thenReturn(Mono.just(true));
        when(participantRepository.findByUserId(userId)).thenReturn(Mono.empty());
        when(participantRepository.save(any(Participant.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(participantService.register(userId))
                .assertNext(participant -> {
                    assertThat(participant.getUserId()).isEqualTo(userId);
                    assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.ACTIVE);
                    assertThat(participant.getCreatedAt()).isNotNull();
                    assertThat(participant.getUpdatedAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void registerRejectsUnknownUser() {
        UUID userId = UUID.randomUUID();
        when(userRepository.existsById(userId)).thenReturn(Mono.just(false));

        StepVerifier.create(participantService.register(userId))
                .expectError(ParticipantConflictException.class)
                .verify();

        verify(participantRepository, never()).save(any());
    }

    @Test
    void registerRejectsASecondParticipantForTheSameUser() {
        UUID userId = UUID.randomUUID();
        Participant existing = new Participant();
        existing.setId(UUID.randomUUID());
        existing.setUserId(userId);
        existing.setStatus(ParticipantStatus.ACTIVE);
        when(userRepository.existsById(userId)).thenReturn(Mono.just(true));
        when(participantRepository.findByUserId(userId)).thenReturn(Mono.just(existing));

        StepVerifier.create(participantService.register(userId))
                .expectError(ParticipantConflictException.class)
                .verify();

        verify(participantRepository, never()).save(any());
    }

    // --- changeStatus: restricted to the three-value enum (FR-007) -----------------------------

    @Test
    void changeStatusCanSetAnyOfTheThreeValues() {
        for (ParticipantStatus status : ParticipantStatus.values()) {
            UUID id = UUID.randomUUID();
            Participant existing = participantOf(id, UUID.randomUUID(), ParticipantStatus.ACTIVE);
            when(participantRepository.findById(id)).thenReturn(Mono.just(existing));
            when(participantRepository.save(any(Participant.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(participantService.changeStatus(id, status))
                    .assertNext(participant -> assertThat(participant.getStatus()).isEqualTo(status))
                    .verifyComplete();
        }
    }

    @Test
    void changeStatusOfUnknownParticipantCompletesEmpty() {
        UUID id = UUID.randomUUID();
        when(participantRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(participantService.changeStatus(id, ParticipantStatus.REVOKED)).verifyComplete();

        verify(participantRepository, never()).save(any());
    }

    // --- setCustomFieldValue: validated against the field's configured type (FR-014) -----------

    @Test
    void setCustomFieldValueRejectsOptionIdsForAFreeTextField() {
        UUID participantId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        Participant participant = participantOf(participantId, UUID.randomUUID(), ParticipantStatus.ACTIVE);
        CustomFieldDefinition definition = definitionOf(fieldId, CustomFieldType.FREE_TEXT);
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(participant));
        when(customFieldDefinitionRepository.findById(fieldId)).thenReturn(Mono.just(definition));

        StepVerifier.create(participantService.setCustomFieldValue(
                        participantId, fieldId, null, List.of(UUID.randomUUID())))
                .expectError(ParticipantConflictException.class)
                .verify();
    }

    @Test
    void setCustomFieldValueRejectsAFreeTextValueForAMultiSelectField() {
        UUID participantId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        Participant participant = participantOf(participantId, UUID.randomUUID(), ParticipantStatus.ACTIVE);
        CustomFieldDefinition definition = definitionOf(fieldId, CustomFieldType.MULTI_SELECT);
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(participant));
        when(customFieldDefinitionRepository.findById(fieldId)).thenReturn(Mono.just(definition));

        StepVerifier.create(
                        participantService.setCustomFieldValue(participantId, fieldId, "some free text", null))
                .expectError(ParticipantConflictException.class)
                .verify();
    }

    @Test
    void setCustomFieldValueRejectsAnOptionNotBelongingToTheField() {
        UUID participantId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        UUID foreignOptionId = UUID.randomUUID();
        Participant participant = participantOf(participantId, UUID.randomUUID(), ParticipantStatus.ACTIVE);
        CustomFieldDefinition definition = definitionOf(fieldId, CustomFieldType.MULTI_SELECT);
        CustomFieldOption ownOption = new CustomFieldOption();
        ownOption.setId(UUID.randomUUID());
        ownOption.setCustomFieldDefinitionId(fieldId);
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(participant));
        when(customFieldDefinitionRepository.findById(fieldId)).thenReturn(Mono.just(definition));
        when(customFieldOptionRepository.findByCustomFieldDefinitionId(fieldId)).thenReturn(Flux.just(ownOption));

        StepVerifier.create(participantService.setCustomFieldValue(
                        participantId, fieldId, null, List.of(foreignOptionId)))
                .expectError(ParticipantConflictException.class)
                .verify();
    }

    @Test
    void setCustomFieldValueSucceedsForAFreeTextField() {
        UUID participantId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        Participant participant = participantOf(participantId, UUID.randomUUID(), ParticipantStatus.ACTIVE);
        CustomFieldDefinition definition = definitionOf(fieldId, CustomFieldType.FREE_TEXT);
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(participant));
        when(customFieldDefinitionRepository.findById(fieldId)).thenReturn(Mono.just(definition));
        stubWriteAlwaysSucceeds();

        StepVerifier.create(
                        participantService.setCustomFieldValue(participantId, fieldId, "Size M", List.of()))
                .expectNext(participant)
                .verifyComplete();
    }

    @Test
    void setCustomFieldValueSucceedsForAMultiSelectField() {
        UUID participantId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        Participant participant = participantOf(participantId, UUID.randomUUID(), ParticipantStatus.ACTIVE);
        CustomFieldDefinition definition = definitionOf(fieldId, CustomFieldType.MULTI_SELECT);
        CustomFieldOption ownOption = new CustomFieldOption();
        ownOption.setId(optionId);
        ownOption.setCustomFieldDefinitionId(fieldId);
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(participant));
        when(customFieldDefinitionRepository.findById(fieldId)).thenReturn(Mono.just(definition));
        when(customFieldOptionRepository.findByCustomFieldDefinitionId(fieldId)).thenReturn(Flux.just(ownOption));
        stubWriteAlwaysSucceeds();

        StepVerifier.create(
                        participantService.setCustomFieldValue(participantId, fieldId, null, List.of(optionId)))
                .expectNext(participant)
                .verifyComplete();
    }

    @Test
    void setCustomFieldValueOfUnknownParticipantCompletesEmpty() {
        UUID participantId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        when(participantRepository.findById(participantId)).thenReturn(Mono.empty());

        StepVerifier.create(participantService.setCustomFieldValue(participantId, fieldId, "x", null))
                .verifyComplete();
    }

    @Test
    void setCustomFieldValueOfUnknownFieldCompletesEmpty() {
        UUID participantId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        Participant participant = participantOf(participantId, UUID.randomUUID(), ParticipantStatus.ACTIVE);
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(participant));
        when(customFieldDefinitionRepository.findById(fieldId)).thenReturn(Mono.empty());

        StepVerifier.create(participantService.setCustomFieldValue(participantId, fieldId, "x", null))
                .verifyComplete();
    }

    // --- incomplete computation (FR-027) ---------------------------------------------------------

    @Test
    void isIncompleteIsTrueWhenARequiredFieldHasNoValue() {
        UUID participantId = UUID.randomUUID();
        stubCount(1L);

        StepVerifier.create(participantService.isIncomplete(participantId))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void isIncompleteIsFalseWhenEveryRequiredFieldHasAValue() {
        UUID participantId = UUID.randomUUID();
        stubCount(0L);

        StepVerifier.create(participantService.isIncomplete(participantId))
                .expectNext(false)
                .verifyComplete();
    }

    // --- selfRegister: honors OrganiserSettings, reactivates rather than duplicating (FR-006, FR-007) -

    @Test
    void selfRegisterRejectsWhenSelfRegistrationIsDisabled() {
        UUID userId = UUID.randomUUID();
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(false, true)));

        StepVerifier.create(participantService.selfRegister(userId))
                .expectError(ParticipantConflictException.class)
                .verify();

        verify(participantRepository, never()).save(any());
    }

    @Test
    void selfRegisterCreatesANewActiveRecordWhenNoneExists() {
        UUID userId = UUID.randomUUID();
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true, true)));
        when(participantRepository.findByUserId(userId)).thenReturn(Mono.empty());
        when(participantRepository.save(any(Participant.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(participantService.selfRegister(userId))
                .assertNext(participant -> {
                    assertThat(participant.getUserId()).isEqualTo(userId);
                    assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.ACTIVE);
                })
                .verifyComplete();
    }

    @Test
    void selfRegisterIsANoOpWhenTheExistingRecordIsAlreadyActive() {
        UUID userId = UUID.randomUUID();
        Participant existing = participantOf(UUID.randomUUID(), userId, ParticipantStatus.ACTIVE);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true, true)));
        when(participantRepository.findByUserId(userId)).thenReturn(Mono.just(existing));

        StepVerifier.create(participantService.selfRegister(userId))
                .expectNext(existing)
                .verifyComplete();

        verify(participantRepository, never()).save(any());
    }

    @Test
    void selfRegisterReactivatesAnExistingRevokedRecordInPlaceRatherThanInsertingANewRow() {
        UUID userId = UUID.randomUUID();
        Participant existing = participantOf(UUID.randomUUID(), userId, ParticipantStatus.REVOKED);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true, true)));
        when(participantRepository.findByUserId(userId)).thenReturn(Mono.just(existing));
        when(participantRepository.save(any(Participant.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(participantService.selfRegister(userId))
                .assertNext(participant -> {
                    assertThat(participant.getId()).isEqualTo(existing.getId());
                    assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.ACTIVE);
                })
                .verifyComplete();

        verify(participantRepository, org.mockito.Mockito.times(1)).save(any(Participant.class));
    }

    // --- selfRevoke: honors OrganiserSettings, removes current Group membership (FR-006, FR-007a) --

    @Test
    void selfRevokeRejectsWhenSelfRevocationIsDisabled() {
        UUID participantId = UUID.randomUUID();
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true, false)));

        StepVerifier.create(participantService.selfRevoke(participantId))
                .expectError(ParticipantConflictException.class)
                .verify();

        verify(participantRepository, never()).save(any());
    }

    @Test
    void selfRevokeSetsRevokedAndRemovesCurrentGroupMembership() {
        UUID participantId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        Participant existing = participantOf(participantId, UUID.randomUUID(), ParticipantStatus.ACTIVE);
        Group group = new Group();
        group.setId(groupId);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true, true)));
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(existing));
        when(participantRepository.save(any(Participant.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(groupService.findActiveGroupForParticipant(participantId)).thenReturn(Mono.just(group));
        when(groupService.removeMember(groupId, participantId)).thenReturn(Mono.just(group));

        StepVerifier.create(participantService.selfRevoke(participantId))
                .assertNext(participant -> assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.REVOKED))
                .verifyComplete();

        verify(groupService).removeMember(groupId, participantId);
    }

    @Test
    void selfRevokeIsANoOpOnTheGroupSideWhenTheParticipantHasNoCurrentGroup() {
        UUID participantId = UUID.randomUUID();
        Participant existing = participantOf(participantId, UUID.randomUUID(), ParticipantStatus.ACTIVE);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true, true)));
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(existing));
        when(participantRepository.save(any(Participant.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(groupService.findActiveGroupForParticipant(participantId)).thenReturn(Mono.empty());

        StepVerifier.create(participantService.selfRevoke(participantId))
                .assertNext(participant -> assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.REVOKED))
                .verifyComplete();

        verify(groupService, never()).removeMember(any(UUID.class), any(UUID.class));
    }

    // --- test helpers ------------------------------------------------------------------------------

    private OrganiserSettings settingsOf(boolean selfRegistrationEnabled, boolean selfRevocationEnabled) {
        OrganiserSettings settings = new OrganiserSettings();
        settings.setId(UUID.randomUUID());
        settings.setSingleton(true);
        settings.setSelfRegistrationEnabled(selfRegistrationEnabled);
        settings.setSelfRevocationEnabled(selfRevocationEnabled);
        settings.setUpdatedAt(Instant.now());
        return settings;
    }

    private Participant participantOf(UUID id, UUID userId, ParticipantStatus status) {
        Participant participant = new Participant();
        participant.setId(id);
        participant.setUserId(userId);
        participant.setStatus(status);
        participant.setCreatedAt(Instant.now());
        participant.setUpdatedAt(Instant.now());
        return participant;
    }

    private CustomFieldDefinition definitionOf(UUID id, CustomFieldType type) {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setId(id);
        definition.setLabel("Field " + id);
        definition.setFieldType(type);
        definition.setRequired(false);
        definition.setCreatedAt(Instant.now());
        definition.setUpdatedAt(Instant.now());
        return definition;
    }

    private void stubWriteAlwaysSucceeds() {
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        // Only a null free-text value takes the bindNull path (see
        // ParticipantService#upsertFreeTextValue); lenient so tests that submit a non-null value
        // don't trip strict-stubbing's "unnecessary stubbing" check.
        lenient().when(executeSpec.bindNull(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());
    }

    @SuppressWarnings("unchecked")
    private void stubCount(long value) {
        RowsFetchSpec<Long> fetch = mock(RowsFetchSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(eq("pid"), any())).thenReturn(executeSpec);
        when(executeSpec.mapValue(Long.class)).thenReturn(fetch);
        when(fetch.one()).thenReturn(Mono.just(value));
    }
}

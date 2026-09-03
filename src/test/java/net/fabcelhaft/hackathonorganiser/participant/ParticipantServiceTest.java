package net.fabcelhaft.hackathonorganiser.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.audit.AuditActor;
import net.fabcelhaft.hackathonorganiser.audit.AuditEntry;
import net.fabcelhaft.hackathonorganiser.audit.AuditEventType;
import net.fabcelhaft.hackathonorganiser.audit.AuditService;
import net.fabcelhaft.hackathonorganiser.audit.AuditSubjectType;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldAnswer;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinition;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinitionRepository;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldOption;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldOptionRepository;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldService;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldType;
import net.fabcelhaft.hackathonorganiser.event.EventPayloadFactory;
import net.fabcelhaft.hackathonorganiser.event.EventPublisher;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettings;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.participant.ProfileFormSubmission.FreeText;
import net.fabcelhaft.hackathonorganiser.participant.ProfileFormSubmission.Options;
import net.fabcelhaft.hackathonorganiser.skill.SkillRepository;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import org.springframework.transaction.reactive.TransactionalOperator;
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

    @Mock
    private CustomFieldService customFieldService;

    @Mock
    private AuditService auditService;

    @Mock
    private EventPublisher eventPublisher;

    @Mock
    private EventPayloadFactory eventPayloadFactory;

    private static final AuditActor ACTOR = new AuditActor(UUID.randomUUID(), true);

    private ParticipantService participantService;

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
            @SuppressWarnings("unchecked")
            public <T> Mono<T> transactional(Mono<T> mono) {
                return mono;
            }
        };
        lenient().when(userRepository.findById(any(UUID.class))).thenReturn(Mono.empty());
        lenient()
                .when(auditService.record(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new AuditEntry()));
        participantService = new ParticipantService(
                participantRepository,
                userRepository,
                skillRepository,
                customFieldDefinitionRepository,
                customFieldOptionRepository,
                databaseClient,
                organiserSettingsService,
                groupService,
                customFieldService,
                transactionalOperator,
                auditService,
                eventPublisher,
                eventPayloadFactory);
    }

    // --- register: single Participant per User (FR-006a), initial status ACTIVE (FR-006b) ------

    @Test
    void registerSucceedsWithInitialStatusActive() {
        UUID userId = UUID.randomUUID();
        when(userRepository.existsById(userId)).thenReturn(Mono.just(true));
        when(participantRepository.findByUserId(userId)).thenReturn(Mono.empty());
        when(participantRepository.save(any(Participant.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(participantService.register(userId, ACTOR))
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

        StepVerifier.create(participantService.register(userId, ACTOR))
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

        StepVerifier.create(participantService.register(userId, ACTOR))
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

            StepVerifier.create(participantService.changeStatus(id, status, ACTOR))
                    .assertNext(participant -> assertThat(participant.getStatus()).isEqualTo(status))
                    .verifyComplete();
        }
    }

    @Test
    void changeStatusOfUnknownParticipantCompletesEmpty() {
        UUID id = UUID.randomUUID();
        when(participantRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(participantService.changeStatus(id, ParticipantStatus.REVOKED, ACTOR)).verifyComplete();

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
                        participantId, fieldId, null, List.of(UUID.randomUUID()), ACTOR))
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
                        participantService.setCustomFieldValue(participantId, fieldId, "some free text", null, ACTOR))
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
                        participantId, fieldId, null, List.of(foreignOptionId), ACTOR))
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
                        participantService.setCustomFieldValue(participantId, fieldId, "Size M", List.of(), ACTOR))
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
                        participantService.setCustomFieldValue(participantId, fieldId, null, List.of(optionId), ACTOR))
                .expectNext(participant)
                .verifyComplete();
    }

    @Test
    void setCustomFieldValueOfUnknownParticipantCompletesEmpty() {
        UUID participantId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        when(participantRepository.findById(participantId)).thenReturn(Mono.empty());

        StepVerifier.create(participantService.setCustomFieldValue(participantId, fieldId, "x", null, ACTOR))
                .verifyComplete();
    }

    @Test
    void setCustomFieldValueOfUnknownFieldCompletesEmpty() {
        UUID participantId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        Participant participant = participantOf(participantId, UUID.randomUUID(), ParticipantStatus.ACTIVE);
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(participant));
        when(customFieldDefinitionRepository.findById(fieldId)).thenReturn(Mono.empty());

        StepVerifier.create(participantService.setCustomFieldValue(participantId, fieldId, "x", null, ACTOR))
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

    // --- selfRegister/selfRevoke: NOT_PARTICIPATED lockout (FR-006a) ---------------------------

    @Test
    void selfRegisterRejectsWhenCallerIsNotParticipated() {
        UUID userId = UUID.randomUUID();
        Participant existing = participantOf(UUID.randomUUID(), userId, ParticipantStatus.NOT_PARTICIPATED);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true, true)));
        when(participantRepository.findByUserId(userId)).thenReturn(Mono.just(existing));

        StepVerifier.create(participantService.selfRegister(userId))
                .expectError(ParticipantConflictException.class)
                .verify();

        verify(participantRepository, never()).save(any());
    }

    @Test
    void selfRevokeRejectsWhenCallerIsNotParticipated() {
        UUID participantId = UUID.randomUUID();
        Participant existing = participantOf(participantId, UUID.randomUUID(), ParticipantStatus.NOT_PARTICIPATED);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true, true)));
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(existing));

        StepVerifier.create(participantService.selfRevoke(participantId, ACTOR))
                .expectError(ParticipantConflictException.class)
                .verify();

        verify(participantRepository, never()).save(any());
    }

    // --- submitRegistration: form-driven registration/reactivation (FR-002, FR-003, FR-004, FR-005) --

    @Test
    void submitRegistrationRejectsWhenSelfRegistrationIsDisabled() {
        UUID userId = UUID.randomUUID();
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(false, true)));

        StepVerifier.create(participantService.submitRegistration(userId, emptySubmission(), ACTOR))
                .expectError(ParticipantConflictException.class)
                .verify();

        verify(participantRepository, never()).save(any());
    }

    @Test
    void submitRegistrationRejectsWhenCallerIsNotParticipated() {
        UUID userId = UUID.randomUUID();
        Participant existing = participantOf(UUID.randomUUID(), userId, ParticipantStatus.NOT_PARTICIPATED);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true, true)));
        when(participantRepository.findByUserId(userId)).thenReturn(Mono.just(existing));

        StepVerifier.create(participantService.submitRegistration(userId, emptySubmission(), ACTOR))
                .expectError(ParticipantConflictException.class)
                .verify();

        verify(participantRepository, never()).save(any());
    }

    @Test
    void submitRegistrationRejectsAMissingRequiredFreeTextFieldWithNoRecordCreated() {
        UUID userId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        CustomFieldDefinition required = requiredDefinitionOf(fieldId, CustomFieldType.FREE_TEXT);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true, true)));
        when(participantRepository.findByUserId(userId)).thenReturn(Mono.empty());
        when(customFieldService.registrationFields()).thenReturn(Flux.just(required));
        stubCapacityGuardPasses();

        StepVerifier.create(participantService.submitRegistration(userId, emptySubmission(), ACTOR))
                .expectError(ParticipantConflictException.class)
                .verify();

        verify(participantRepository, never()).save(any());
    }

    @Test
    void submitRegistrationRejectsMoreThanOneOptionForASingleSelectField() {
        UUID userId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        UUID option1 = UUID.randomUUID();
        UUID option2 = UUID.randomUUID();
        CustomFieldDefinition singleSelect = definitionOf(fieldId, CustomFieldType.SINGLE_SELECT);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true, true)));
        when(participantRepository.findByUserId(userId)).thenReturn(Mono.empty());
        when(customFieldService.registrationFields()).thenReturn(Flux.just(singleSelect));
        stubCapacityGuardPasses();

        ProfileFormSubmission submission =
                new ProfileFormSubmission(Map.of(fieldId, new Options(Set.of(option1, option2))), List.of());

        StepVerifier.create(participantService.submitRegistration(userId, submission, ACTOR))
                .expectError(ParticipantConflictException.class)
                .verify();

        verify(participantRepository, never()).save(any());
    }

    @Test
    void submitRegistrationRejectsACountryCodeNotInTheCatalog() {
        UUID userId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        CustomFieldDefinition country = definitionOf(fieldId, CustomFieldType.COUNTRY);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true, true)));
        when(participantRepository.findByUserId(userId)).thenReturn(Mono.empty());
        when(customFieldService.registrationFields()).thenReturn(Flux.just(country));
        stubCapacityGuardPasses();

        ProfileFormSubmission submission =
                new ProfileFormSubmission(Map.of(fieldId, new FreeText("ZZ")), List.of());

        StepVerifier.create(participantService.submitRegistration(userId, submission, ACTOR))
                .expectError(ParticipantConflictException.class)
                .verify();

        verify(participantRepository, never()).save(any());
    }

    @Test
    void submitRegistrationAcceptsZeroSkillsAndCreatesAnActiveRecord() {
        UUID userId = UUID.randomUUID();
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true, true)));
        when(participantRepository.findByUserId(userId)).thenReturn(Mono.empty());
        when(customFieldService.registrationFields()).thenReturn(Flux.empty());
        when(participantRepository.save(any(Participant.class)))
                .thenAnswer(invocation -> Mono.just(withId(invocation.getArgument(0))));
        stubWriteAlwaysSucceeds();

        StepVerifier.create(participantService.submitRegistration(userId, emptySubmission(), ACTOR))
                .assertNext(participant -> {
                    assertThat(participant.getUserId()).isEqualTo(userId);
                    assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.ACTIVE);
                })
                .verifyComplete();
    }

    @Test
    void submitRegistrationReactivatesAnExistingRevokedRecordInPlace() {
        UUID userId = UUID.randomUUID();
        Participant existing = participantOf(UUID.randomUUID(), userId, ParticipantStatus.REVOKED);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true, true)));
        when(participantRepository.findByUserId(userId)).thenReturn(Mono.just(existing));
        when(customFieldService.registrationFields()).thenReturn(Flux.empty());
        when(participantRepository.save(any(Participant.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        stubWriteAlwaysSucceeds();

        StepVerifier.create(participantService.submitRegistration(userId, emptySubmission(), ACTOR))
                .assertNext(participant -> {
                    assertThat(participant.getId()).isEqualTo(existing.getId());
                    assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.ACTIVE);
                })
                .verifyComplete();
    }

    // --- submitSelfEdit: reuses registration validation, no create/reactivate branching (FR-022) --

    @Test
    void submitSelfEditRejectsWhenSelfEditIsCurrentlyDisabled() {
        UUID participantId = UUID.randomUUID();
        OrganiserSettings settings = settingsOf(true, true);
        settings.setSelfEditEnabled(false);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settings));

        StepVerifier.create(participantService.submitSelfEdit(participantId, emptySubmission(), ACTOR))
                .expectError(ParticipantConflictException.class)
                .verify();

        verify(participantRepository, never()).findById(any(UUID.class));
    }

    @Test
    void submitSelfEditRejectsWhenTheParticipantIsNotParticipated() {
        UUID participantId = UUID.randomUUID();
        Participant existing =
                participantOf(participantId, UUID.randomUUID(), ParticipantStatus.NOT_PARTICIPATED);
        OrganiserSettings settings = settingsOf(true, true);
        settings.setSelfEditEnabled(true);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settings));
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(existing));

        StepVerifier.create(participantService.submitSelfEdit(participantId, emptySubmission(), ACTOR))
                .expectError(ParticipantConflictException.class)
                .verify();

        verify(participantRepository, never()).save(any());
    }

    @Test
    void submitSelfEditAppliesTheSameFieldValidationAsRegistration() {
        UUID participantId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        Participant existing = participantOf(participantId, UUID.randomUUID(), ParticipantStatus.ACTIVE);
        CustomFieldDefinition required = definitionOf(fieldId, CustomFieldType.FREE_TEXT);
        required.setRequired(true);
        OrganiserSettings settings = settingsOf(true, true);
        settings.setSelfEditEnabled(true);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settings));
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(existing));
        when(customFieldService.registrationFields()).thenReturn(Flux.just(required));

        StepVerifier.create(participantService.submitSelfEdit(participantId, emptySubmission(), ACTOR))
                .expectError(ParticipantConflictException.class)
                .verify();

        verify(participantRepository, never()).save(any());
    }

    @Test
    void submitSelfEditPersistsChangesInPlaceWithNoNewRecord() {
        UUID participantId = UUID.randomUUID();
        Participant existing = participantOf(participantId, UUID.randomUUID(), ParticipantStatus.ACTIVE);
        OrganiserSettings settings = settingsOf(true, true);
        settings.setSelfEditEnabled(true);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settings));
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(existing));
        when(customFieldService.registrationFields()).thenReturn(Flux.empty());
        stubWriteAlwaysSucceeds();

        StepVerifier.create(participantService.submitSelfEdit(participantId, emptySubmission(), ACTOR))
                .expectNext(existing)
                .verifyComplete();

        verify(participantRepository, never()).save(any());
    }

    private ProfileFormSubmission emptySubmission() {
        return new ProfileFormSubmission(Map.of(), List.of());
    }

    private CustomFieldDefinition requiredDefinitionOf(UUID id, CustomFieldType type) {
        CustomFieldDefinition definition = definitionOf(id, type);
        definition.setRequired(true);
        return definition;
    }

    // --- selfRevoke: honors OrganiserSettings, removes current Group membership (FR-006, FR-007a) --

    @Test
    void selfRevokeRejectsWhenSelfRevocationIsDisabled() {
        UUID participantId = UUID.randomUUID();
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true, false)));

        StepVerifier.create(participantService.selfRevoke(participantId, ACTOR))
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
        when(groupService.removeMember(groupId, participantId, ACTOR)).thenReturn(Mono.just(group));

        StepVerifier.create(participantService.selfRevoke(participantId, ACTOR))
                .assertNext(participant -> assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.REVOKED))
                .verifyComplete();

        verify(groupService).removeMember(groupId, participantId, ACTOR);
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

        StepVerifier.create(participantService.selfRevoke(participantId, ACTOR))
                .assertNext(participant -> assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.REVOKED))
                .verifyComplete();

        verify(groupService, never()).removeMember(any(UUID.class), any(UUID.class), any());
    }

    // --- delete: only the Participant, blocked while it belongs to an active Group -------------

    @Test
    void deleteRemovesTheParticipantWhenNotInAGroup() {
        UUID participantId = UUID.randomUUID();
        Participant existing = participantOf(participantId, UUID.randomUUID(), ParticipantStatus.ACTIVE);
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(existing));
        when(groupService.findActiveGroupForParticipant(participantId)).thenReturn(Mono.empty());
        when(participantRepository.deleteById(participantId)).thenReturn(Mono.empty());
        stubWriteAlwaysSucceeds();

        StepVerifier.create(participantService.delete(participantId, ACTOR)).verifyComplete();

        verify(participantRepository).deleteById(participantId);
    }

    @Test
    void deleteRejectsAParticipantWhoIsInAGroup() {
        UUID participantId = UUID.randomUUID();
        Participant existing = participantOf(participantId, UUID.randomUUID(), ParticipantStatus.ACTIVE);
        Group group = new Group();
        group.setId(UUID.randomUUID());
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(existing));
        when(groupService.findActiveGroupForParticipant(participantId)).thenReturn(Mono.just(group));

        StepVerifier.create(participantService.delete(participantId, ACTOR))
                .expectError(ParticipantConflictException.class)
                .verify();

        verify(participantRepository, never()).deleteById(any(UUID.class));
    }

    @Test
    void deleteIsANoOpForAnUnknownParticipant() {
        UUID participantId = UUID.randomUUID();
        when(participantRepository.findById(participantId)).thenReturn(Mono.empty());

        StepVerifier.create(participantService.delete(participantId, ACTOR)).verifyComplete();

        verify(participantRepository, never()).deleteById(any(UUID.class));
    }

    // --- Audit recording (T012, FR-001, FR-002a) ----------------------------------------------

    @Test
    void registerRecordsACreatedAuditEntryWithNoOldOrNewValue() {
        UUID userId = UUID.randomUUID();
        when(userRepository.existsById(userId)).thenReturn(Mono.just(true));
        when(participantRepository.findByUserId(userId)).thenReturn(Mono.empty());
        when(participantRepository.save(any(Participant.class)))
                .thenAnswer(invocation -> Mono.just(withId(invocation.getArgument(0))));

        Participant registered = participantService.register(userId, ACTOR).block();

        verify(auditService)
                .record(
                        eq(AuditEventType.CREATED),
                        eq(ACTOR),
                        eq(AuditSubjectType.PARTICIPANT),
                        eq(registered.getId()),
                        anyString(),
                        isNull(),
                        isNull(),
                        isNull());
    }

    @Test
    void changeStatusRecordsAStatusChangedAuditEntryWithRealOldAndNewValues() {
        UUID id = UUID.randomUUID();
        Participant existing = participantOf(id, UUID.randomUUID(), ParticipantStatus.ACTIVE);
        when(participantRepository.findById(id)).thenReturn(Mono.just(existing));
        when(participantRepository.save(any(Participant.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        participantService.changeStatus(id, ParticipantStatus.REVOKED, ACTOR).block();

        verify(auditService)
                .record(
                        eq(AuditEventType.STATUS_CHANGED),
                        eq(ACTOR),
                        eq(AuditSubjectType.PARTICIPANT),
                        eq(id),
                        anyString(),
                        eq("ACTIVE"),
                        eq("REVOKED"),
                        isNull());
    }

    @Test
    void replaceSkillsRecordsAnEditedAuditEntryWithNoOldOrNewValue() {
        UUID participantId = UUID.randomUUID();
        Participant existing = participantOf(participantId, UUID.randomUUID(), ParticipantStatus.ACTIVE);
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(existing));
        stubWriteAlwaysSucceeds();

        participantService.replaceSkills(participantId, List.of(), ACTOR).block();

        verify(auditService)
                .record(
                        eq(AuditEventType.EDITED),
                        eq(ACTOR),
                        eq(AuditSubjectType.PARTICIPANT),
                        eq(participantId),
                        anyString(),
                        isNull(),
                        isNull(),
                        isNull());
    }

    @Test
    void setCustomFieldValueRecordsAnEditedAuditEntryWithNoOldOrNewValue() {
        UUID participantId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        Participant participant = participantOf(participantId, UUID.randomUUID(), ParticipantStatus.ACTIVE);
        CustomFieldDefinition definition = definitionOf(fieldId, CustomFieldType.FREE_TEXT);
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(participant));
        when(customFieldDefinitionRepository.findById(fieldId)).thenReturn(Mono.just(definition));
        stubWriteAlwaysSucceeds();

        participantService.setCustomFieldValue(participantId, fieldId, "Size M", List.of(), ACTOR).block();

        verify(auditService)
                .record(
                        eq(AuditEventType.EDITED),
                        eq(ACTOR),
                        eq(AuditSubjectType.PARTICIPANT),
                        eq(participantId),
                        anyString(),
                        isNull(),
                        isNull(),
                        isNull());
    }

    @Test
    void submitRegistrationRecordsACreatedAuditEntryWithNoOldOrNewValue() {
        UUID userId = UUID.randomUUID();
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true, true)));
        when(participantRepository.findByUserId(userId)).thenReturn(Mono.empty());
        when(customFieldService.registrationFields()).thenReturn(Flux.empty());
        when(participantRepository.save(any(Participant.class)))
                .thenAnswer(invocation -> Mono.just(withId(invocation.getArgument(0))));
        stubWriteAlwaysSucceeds();

        Participant created = participantService
                .submitRegistration(userId, emptySubmission(), ACTOR)
                .block();

        verify(auditService)
                .record(
                        eq(AuditEventType.CREATED),
                        eq(ACTOR),
                        eq(AuditSubjectType.PARTICIPANT),
                        eq(created.getId()),
                        anyString(),
                        isNull(),
                        isNull(),
                        isNull());
    }

    @Test
    void submitSelfEditRecordsAnEditedAuditEntryWithNoOldOrNewValue() {
        UUID participantId = UUID.randomUUID();
        Participant existing = participantOf(participantId, UUID.randomUUID(), ParticipantStatus.ACTIVE);
        OrganiserSettings settings = settingsOf(true, true);
        settings.setSelfEditEnabled(true);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settings));
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(existing));
        when(customFieldService.registrationFields()).thenReturn(Flux.empty());
        stubWriteAlwaysSucceeds();

        participantService.submitSelfEdit(participantId, emptySubmission(), ACTOR).block();

        verify(auditService)
                .record(
                        eq(AuditEventType.EDITED),
                        eq(ACTOR),
                        eq(AuditSubjectType.PARTICIPANT),
                        eq(participantId),
                        anyString(),
                        isNull(),
                        isNull(),
                        isNull());
    }

    @Test
    void selfRevokeRecordsAStatusChangedAuditEntryWithRealOldAndNewValues() {
        UUID participantId = UUID.randomUUID();
        Participant existing = participantOf(participantId, UUID.randomUUID(), ParticipantStatus.ACTIVE);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(true, true)));
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(existing));
        when(participantRepository.save(any(Participant.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(groupService.findActiveGroupForParticipant(participantId)).thenReturn(Mono.empty());

        participantService.selfRevoke(participantId, ACTOR).block();

        verify(auditService)
                .record(
                        eq(AuditEventType.STATUS_CHANGED),
                        eq(ACTOR),
                        eq(AuditSubjectType.PARTICIPANT),
                        eq(participantId),
                        anyString(),
                        eq("ACTIVE"),
                        eq("REVOKED"),
                        isNull());
    }

    @Test
    void deleteRecordsADeletedAuditEntryBeforeIssuingTheRepositoryDelete() {
        UUID participantId = UUID.randomUUID();
        Participant existing = participantOf(participantId, UUID.randomUUID(), ParticipantStatus.ACTIVE);
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(existing));
        when(groupService.findActiveGroupForParticipant(participantId)).thenReturn(Mono.empty());
        when(participantRepository.deleteById(participantId)).thenReturn(Mono.empty());
        stubWriteAlwaysSucceeds();

        StepVerifier.create(participantService.delete(participantId, ACTOR)).verifyComplete();

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(auditService, participantRepository);
        inOrder.verify(auditService)
                .record(
                        eq(AuditEventType.DELETED),
                        eq(ACTOR),
                        eq(AuditSubjectType.PARTICIPANT),
                        eq(participantId),
                        anyString(),
                        isNull(),
                        isNull(),
                        isNull());
        inOrder.verify(participantRepository).deleteById(participantId);
    }

    // --- findDirectoryListing: only ACTIVE, alphabetical by display name (FR-027, FR-027a) -------
    // Field-level Public/Overview resolution is exercised end-to-end against a real database by
    // ParticipantsDirectoryManagementIT — these unit tests use zero Overview-marked definitions so
    // the ordering/ACTIVE-filtering logic under test needs no per-field DatabaseClient mocking.

    @Test
    void findDirectoryListingIncludesOnlyActiveParticipantsOrderedAlphabetically() {
        when(customFieldDefinitionRepository.findAll()).thenReturn(Flux.empty());
        Participant zoe = participantOf(UUID.randomUUID(), UUID.randomUUID(), ParticipantStatus.ACTIVE);
        Participant alice = participantOf(UUID.randomUUID(), UUID.randomUUID(), ParticipantStatus.ACTIVE);
        Participant revoked = participantOf(UUID.randomUUID(), UUID.randomUUID(), ParticipantStatus.REVOKED);
        when(participantRepository.findAll()).thenReturn(Flux.just(zoe, alice, revoked));
        when(userRepository.findById(zoe.getUserId())).thenReturn(Mono.just(userOf(zoe.getUserId(), "Zoe")));
        when(userRepository.findById(alice.getUserId())).thenReturn(Mono.just(userOf(alice.getUserId(), "Alice")));
        stubCustomFieldAnswers(zoe.getId(), List.of());
        stubCustomFieldAnswers(alice.getId(), List.of());

        StepVerifier.create(participantService.findDirectoryListing())
                .assertNext(row -> assertThat(row.displayName()).isEqualTo("Alice"))
                .assertNext(row -> assertThat(row.displayName()).isEqualTo("Zoe"))
                .verifyComplete();

        verify(userRepository, never()).findById(revoked.getUserId());
    }

    // --- findDetailForViewer: self/organiser see everything; other-viewer sees only what's shared -

    @Test
    void findDetailForViewerGivesTheOwnerSkillsRegardlessOfSkillVisibilitySetting() {
        UUID participantId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        Participant participant = participantOf(participantId, ownerUserId, ParticipantStatus.ACTIVE);
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(participant));
        when(userRepository.findById(ownerUserId)).thenReturn(Mono.just(userOf(ownerUserId, "Owner")));
        when(customFieldDefinitionRepository.findAll()).thenReturn(Flux.empty());
        stubCustomFieldAnswers(participantId, List.of());
        stubSkills(participantId, List.of());
        OrganiserSettings settings = settingsOf(true, true);
        settings.setSkillVisibilityEnabled(false);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settings));

        StepVerifier.create(participantService.findDetailForViewer(participantId, ownerUserId, false))
                .assertNext(detail -> {
                    assertThat(detail.self()).isTrue();
                    assertThat(detail.skillsVisibleToViewer()).isTrue();
                    assertThat(detail.skillsVisibleToOthers()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void findDetailForViewerHidesSkillsFromAnOtherViewerWhenSkillVisibilityIsDisabled() {
        UUID participantId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        UUID viewerUserId = UUID.randomUUID();
        Participant participant = participantOf(participantId, ownerUserId, ParticipantStatus.ACTIVE);
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(participant));
        when(userRepository.findById(ownerUserId)).thenReturn(Mono.just(userOf(ownerUserId, "Owner")));
        when(customFieldDefinitionRepository.findAll()).thenReturn(Flux.empty());
        stubCustomFieldAnswers(participantId, List.of());
        stubSkills(participantId, List.of());
        OrganiserSettings settings = settingsOf(true, true);
        settings.setSkillVisibilityEnabled(false);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settings));

        StepVerifier.create(participantService.findDetailForViewer(participantId, viewerUserId, false))
                .assertNext(detail -> {
                    assertThat(detail.self()).isFalse();
                    assertThat(detail.skillsVisibleToViewer()).isFalse();
                    assertThat(detail.skillsVisibleToOthers()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void findDetailForViewerGivesAnOrganiserSkillsRegardlessOfSkillVisibilitySetting() {
        UUID participantId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        UUID organiserUserId = UUID.randomUUID();
        Participant participant = participantOf(participantId, ownerUserId, ParticipantStatus.ACTIVE);
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(participant));
        when(userRepository.findById(ownerUserId)).thenReturn(Mono.just(userOf(ownerUserId, "Owner")));
        when(customFieldDefinitionRepository.findAll()).thenReturn(Flux.empty());
        stubCustomFieldAnswers(participantId, List.of());
        stubSkills(participantId, List.of());
        OrganiserSettings settings = settingsOf(true, true);
        settings.setSkillVisibilityEnabled(false);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settings));

        StepVerifier.create(participantService.findDetailForViewer(participantId, organiserUserId, true))
                .assertNext(detail -> {
                    assertThat(detail.self()).isFalse();
                    assertThat(detail.organiserView()).isTrue();
                    assertThat(detail.skillsVisibleToViewer()).isTrue();
                    assertThat(detail.skillsVisibleToOthers()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void findDetailForViewerCompletesEmptyForANonActiveParticipantViewedByAnyoneOtherThanThemselves() {
        UUID participantId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        UUID viewerUserId = UUID.randomUUID();
        Participant participant = participantOf(participantId, ownerUserId, ParticipantStatus.REVOKED);
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(participant));
        when(userRepository.findById(ownerUserId)).thenReturn(Mono.just(userOf(ownerUserId, "Owner")));

        StepVerifier.create(participantService.findDetailForViewer(participantId, viewerUserId, false))
                .verifyComplete();
    }

    @Test
    void findDetailForViewerMarksEachFieldVisibleToOthersByItsPublicFlagAndOmitsNonPublicFieldsFromAnOtherViewer() {
        UUID participantId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        UUID viewerUserId = UUID.randomUUID();
        UUID publicFieldId = UUID.randomUUID();
        UUID privateFieldId = UUID.randomUUID();
        Participant participant = participantOf(participantId, ownerUserId, ParticipantStatus.ACTIVE);
        CustomFieldDefinition publicField = definitionOf(publicFieldId, CustomFieldType.FREE_TEXT);
        publicField.setPublic_(true);
        CustomFieldDefinition privateField = definitionOf(privateFieldId, CustomFieldType.FREE_TEXT);
        privateField.setPublic_(false);
        when(participantRepository.findById(participantId)).thenReturn(Mono.just(participant));
        when(userRepository.findById(ownerUserId)).thenReturn(Mono.just(userOf(ownerUserId, "Owner")));
        when(customFieldDefinitionRepository.findAll()).thenReturn(Flux.just(publicField, privateField));
        stubCustomFieldAnswers(participantId, List.of(publicField, privateField));
        stubSkills(participantId, List.of());
        OrganiserSettings settings = settingsOf(true, true);
        settings.setSkillVisibilityEnabled(false);
        when(organiserSettingsService.current()).thenReturn(Mono.just(settings));

        StepVerifier.create(participantService.findDetailForViewer(participantId, ownerUserId, false))
                .assertNext(detail -> {
                    assertThat(detail.fields()).hasSize(2);
                    assertThat(detail.fields())
                            .extracting(ParticipantService.ViewerFieldValue::visibleToOthers)
                            .containsExactlyInAnyOrder(true, false);
                })
                .verifyComplete();

        StepVerifier.create(participantService.findDetailForViewer(participantId, viewerUserId, false))
                .assertNext(detail -> {
                    assertThat(detail.fields()).hasSize(1);
                    assertThat(detail.fields().get(0).definition().getId()).isEqualTo(publicFieldId);
                })
                .verifyComplete();
    }

    /**
     * Stubs {@code customFieldService.answersFor(...)} — the per-Participant Custom Field
     * value/selection assembly moved out of {@code ParticipantService} into {@code
     * CustomFieldService} (research.md §10 of feature 007) — with a blank answer per given
     * definition, exactly as it would compute for a Participant with no stored values.
     */
    private void stubCustomFieldAnswers(UUID participantId, List<CustomFieldDefinition> fields) {
        List<CustomFieldAnswer> answers =
                fields.stream().map(field -> new CustomFieldAnswer(field, List.of(), "", List.of())).toList();
        lenient().when(customFieldService.answersFor(eq(participantId), eq(fields))).thenReturn(Mono.just(answers));
    }

    private void stubSkills(UUID participantId, List<net.fabcelhaft.hackathonorganiser.skill.Skill> skills) {
        RowsFetchSpec<UUID> fetch = mock(RowsFetchSpec.class);
        lenient().when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        lenient().when(executeSpec.bind(eq("pid"), any())).thenReturn(executeSpec);
        lenient().when(executeSpec.mapValue(UUID.class)).thenReturn(fetch);
        lenient()
                .when(fetch.all())
                .thenReturn(Flux.fromIterable(
                        skills.stream().map(net.fabcelhaft.hackathonorganiser.skill.Skill::getId).toList()));
        if (!skills.isEmpty()) {
            when(skillRepository.findAllById(any(java.util.Collection.class))).thenReturn(Flux.fromIterable(skills));
        }
    }

    private net.fabcelhaft.hackathonorganiser.user.User userOf(UUID id, String displayName) {
        net.fabcelhaft.hackathonorganiser.user.User user = new net.fabcelhaft.hackathonorganiser.user.User();
        user.setId(id);
        user.setDisplayName(displayName);
        user.setOidcSubject("sub-" + id);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return user;
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

    private Participant withId(Participant participant) {
        if (participant.getId() == null) {
            participant.setId(UUID.randomUUID());
        }
        return participant;
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

    /** Stubs just enough for {@code acquireLockAndCheckCapacity}'s advisory-lock statement to pass. */
    private void stubCapacityGuardPasses() {
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());
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

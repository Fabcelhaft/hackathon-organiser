package net.fabcelhaft.hackathonorganiser.participant;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.audit.AuditActor;
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
import net.fabcelhaft.hackathonorganiser.customfield.IsoCountryCatalog;
import net.fabcelhaft.hackathonorganiser.event.EventPayloadFactory;
import net.fabcelhaft.hackathonorganiser.event.EventPublisher;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.participant.ProfileFormSubmission.FieldAnswer;
import net.fabcelhaft.hackathonorganiser.participant.ProfileFormSubmission.FreeText;
import net.fabcelhaft.hackathonorganiser.participant.ProfileFormSubmission.Options;
import net.fabcelhaft.hackathonorganiser.skill.Skill;
import net.fabcelhaft.hackathonorganiser.skill.SkillRepository;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Organiser-facing operations on {@link Participant} records (T040): registration with the
 * single-Participant-per-User guard (FR-006a) and the {@code ACTIVE} initial status (FR-006b),
 * status changes (FR-007), Skill selection replace (FR-009), and Custom Field value set/validate
 * (FR-013, FR-014) — plus the read-time "incomplete" computation (FR-027).
 *
 * <p>{@code custom_field_values} and {@code custom_field_value_options} are composite-key tables
 * that a single-column-{@code @Id} {@link org.springframework.data.repository.reactive.ReactiveCrudRepository}
 * cannot back (research.md §4), so this service manipulates them directly via {@link DatabaseClient}
 * rather than through a repository — the same approach {@code participant_skills} uses here for
 * the same reason.
 *
 * <p><b>Audit (006-audit-trail, FR-001, FR-002a):</b> every mutating method here takes an explicit
 * {@link AuditActor} and records a corresponding {@link AuditService} entry on success —
 * {@code changeStatus}/{@code selfRevoke} carry the real old/new status values (FR-002a's
 * high-stakes fields); {@code delete} records its {@code DELETED} entry before issuing the
 * repository delete, in the same transaction, so the entry's {@code subject_label} snapshot is
 * never lost even though {@code subject_id} carries no foreign key (research.md §3).
 */
@Service
public class ParticipantService {

    private final ParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final SkillRepository skillRepository;
    private final CustomFieldDefinitionRepository customFieldDefinitionRepository;
    private final CustomFieldOptionRepository customFieldOptionRepository;
    private final DatabaseClient databaseClient;
    private final OrganiserSettingsService organiserSettingsService;
    private final GroupService groupService;
    private final CustomFieldService customFieldService;
    private final TransactionalOperator transactionalOperator;
    private final AuditService auditService;
    private final EventPublisher eventPublisher;
    private final EventPayloadFactory eventPayloadFactory;

    /**
     * Distinguished from a field-validation {@link ParticipantConflictException} by exact message
     * equality: {@code RegistrationController}/{@code ProfileController} redirect home with this
     * exact text as the flash message (FR-006a) rather than re-rendering the submitted form.
     */
    public static final String NOT_PARTICIPATED_MESSAGE =
            "Your participation status was set by an Organiser. Only an Organiser can change it.";

    /** See {@link #NOT_PARTICIPATED_MESSAGE} — same redirect-not-rerender distinction (FR-006). */
    public static final String SELF_REGISTRATION_DISABLED_MESSAGE = "Self-registration is currently disabled";

    /** See {@link #NOT_PARTICIPATED_MESSAGE} — same redirect-not-rerender distinction (FR-023, FR-024). */
    public static final String SELF_EDIT_DISABLED_MESSAGE = "Self-edit is currently disabled";

    public ParticipantService(
            ParticipantRepository participantRepository,
            UserRepository userRepository,
            SkillRepository skillRepository,
            CustomFieldDefinitionRepository customFieldDefinitionRepository,
            CustomFieldOptionRepository customFieldOptionRepository,
            DatabaseClient databaseClient,
            OrganiserSettingsService organiserSettingsService,
            GroupService groupService,
            CustomFieldService customFieldService,
            TransactionalOperator transactionalOperator,
            AuditService auditService,
            EventPublisher eventPublisher,
            EventPayloadFactory eventPayloadFactory) {
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.skillRepository = skillRepository;
        this.customFieldDefinitionRepository = customFieldDefinitionRepository;
        this.customFieldOptionRepository = customFieldOptionRepository;
        this.databaseClient = databaseClient;
        this.organiserSettingsService = organiserSettingsService;
        this.groupService = groupService;
        this.customFieldService = customFieldService;
        this.transactionalOperator = transactionalOperator;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.eventPayloadFactory = eventPayloadFactory;
    }

    // --- Self-service registration/revocation (FR-003, FR-004, FR-006, FR-007, FR-007a) --------

    /**
     * A user's own self-service registration/reactivation action (FR-003, FR-007), rejecting with
     * a friendly {@link ParticipantConflictException} if self-registration is currently disabled
     * (FR-006) — re-read from {@link OrganiserSettingsService} on every call, never cached.
     *
     * <p>Deliberately not a thin wrapper around {@link #register(UUID)}: that method rejects any
     * pre-existing Participant record outright, which is correct for its own organiser-driven
     * caller but wrong here — FR-007 requires an existing non-{@code ACTIVE} record (in
     * particular {@code REVOKED}) be reactivated to {@code ACTIVE} in place, never a new row. An
     * already-{@code ACTIVE} record is a no-op (Edge Cases: a double-submit must not create a
     * duplicate or otherwise mutate the record).
     */
    public Mono<Participant> selfRegister(UUID userId) {
        return organiserSettingsService.current().flatMap(settings -> {
            if (!settings.isSelfRegistrationEnabled()) {
                return Mono.error(new ParticipantConflictException(SELF_REGISTRATION_DISABLED_MESSAGE));
            }
            return participantRepository
                    .findByUserId(userId)
                    .flatMap(existing -> {
                        if (existing.getStatus() == ParticipantStatus.NOT_PARTICIPATED) {
                            return Mono.<Participant>error(new ParticipantConflictException(NOT_PARTICIPATED_MESSAGE));
                        }
                        if (existing.getStatus() == ParticipantStatus.ACTIVE) {
                            return Mono.just(existing);
                        }
                        existing.setStatus(ParticipantStatus.ACTIVE);
                        existing.setUpdatedAt(Instant.now());
                        return participantRepository.save(existing);
                    })
                    .switchIfEmpty(Mono.defer(() -> participantRepository.save(newParticipant(userId))));
        });
    }

    /**
     * A user's own self-service revocation action (FR-004), rejecting with a friendly
     * {@link ParticipantConflictException} if self-revocation is currently disabled (FR-006) —
     * re-read from {@link OrganiserSettingsService} on every call, never cached. On success, sets
     * {@code status = REVOKED} and removes the Participant's current Group membership if one
     * exists (FR-007a), composing {@link GroupService#findActiveGroupForParticipant} and {@link
     * GroupService#removeMember} rather than duplicating that logic (research.md §10); a no-op on
     * the Group side when the Participant has no current Group. Completes empty if no Participant
     * exists with the given id.
     */
    public Mono<Participant> selfRevoke(UUID participantId, AuditActor actor) {
        return organiserSettingsService.current().flatMap(settings -> {
            if (!settings.isSelfRevocationEnabled()) {
                return Mono.error(new ParticipantConflictException("Self-revocation is currently disabled"));
            }
            return participantRepository.findById(participantId).flatMap(participant -> {
                if (participant.getStatus() == ParticipantStatus.NOT_PARTICIPATED) {
                    return Mono.error(new ParticipantConflictException(NOT_PARTICIPATED_MESSAGE));
                }
                ParticipantStatus oldStatus = participant.getStatus();
                participant.setStatus(ParticipantStatus.REVOKED);
                participant.setUpdatedAt(Instant.now());
                return participantRepository
                        .save(participant)
                        .flatMap(saved -> groupService
                                .findActiveGroupForParticipant(participantId)
                                .flatMap(group -> groupService.removeMember(group.getId(), participantId, actor))
                                .thenReturn(saved))
                        .flatMap(saved -> recordStatusChanged(saved, actor, oldStatus, ParticipantStatus.REVOKED));
            });
        });
    }

    // --- Registration (FR-006a, FR-006b) --------------------------------------------------------

    /**
     * Registers a User as a Participant with initial {@code status = ACTIVE} (FR-006b), rejecting
     * an unknown {@code userId} or a User who already has a Participant record (FR-006a) with a
     * friendly {@link ParticipantConflictException}.
     */
    public Mono<Participant> register(UUID userId, AuditActor actor) {
        return userRepository.existsById(userId).flatMap(userExists -> {
            if (!userExists) {
                return Mono.error(new ParticipantConflictException("Unknown user: " + userId));
            }
            return participantRepository
                    .findByUserId(userId)
                    .<Participant>flatMap(existing -> Mono.error(new ParticipantConflictException(
                            "This user is already registered as a Participant")))
                    .switchIfEmpty(Mono.defer(() -> participantRepository
                            .save(newParticipant(userId))
                            .flatMap(saved -> recordCreated(saved, actor))));
        });
    }

    private Mono<Participant> recordCreated(Participant participant, AuditActor actor) {
        return userDisplayName(participant.getUserId())
                .flatMap(name -> auditService.record(
                        AuditEventType.CREATED,
                        actor,
                        AuditSubjectType.PARTICIPANT,
                        participant.getId(),
                        name,
                        null,
                        null,
                        null))
                .doOnSuccess(v -> eventPublisher.publish(eventPayloadFactory.participantRegistered(participant)))
                .thenReturn(participant);
    }

    private Mono<Participant> recordEdited(Participant participant, AuditActor actor) {
        return userDisplayName(participant.getUserId())
                .flatMap(name -> auditService.record(
                        AuditEventType.EDITED,
                        actor,
                        AuditSubjectType.PARTICIPANT,
                        participant.getId(),
                        name,
                        null,
                        null,
                        null))
                .thenReturn(participant);
    }

    private Mono<Participant> recordStatusChanged(
            Participant participant, AuditActor actor, ParticipantStatus oldStatus, ParticipantStatus newStatus) {
        return userDisplayName(participant.getUserId())
                .flatMap(name -> auditService.record(
                        AuditEventType.STATUS_CHANGED,
                        actor,
                        AuditSubjectType.PARTICIPANT,
                        participant.getId(),
                        name,
                        oldStatus.name(),
                        newStatus.name(),
                        null))
                .doOnSuccess(v -> publishStatusChangeEvent(participant, newStatus))
                .thenReturn(participant);
    }

    /** Publishes {@code PARTICIPANT_REVOKED}/{@code PARTICIPANT_NOT_PARTICIPATED} (research.md §6). */
    private void publishStatusChangeEvent(Participant participant, ParticipantStatus newStatus) {
        if (newStatus == ParticipantStatus.REVOKED) {
            eventPublisher.publish(eventPayloadFactory.participantRevoked(participant));
        } else if (newStatus == ParticipantStatus.NOT_PARTICIPATED) {
            eventPublisher.publish(eventPayloadFactory.participantNotParticipated(participant));
        }
    }

    private Mono<String> userDisplayName(UUID userId) {
        return userRepository.findById(userId).map(User::getDisplayName).defaultIfEmpty("Unknown user");
    }

    /** The current user's own Participant record, if any — used by the homepage (FR-001, FR-007a). */
    public Mono<Participant> findByUserId(UUID userId) {
        return participantRepository.findByUserId(userId);
    }

    // --- Form-driven registration & self-edit (FR-001-FR-010, FR-021-FR-024) --------------------

    /**
     * The form's entry point (FR-001, FR-005), superseding 003's bare {@link #selfRegister}: inside
     * one {@link TransactionalOperator}-wrapped transaction (research.md §4), rejects with {@link
     * ParticipantConflictException} if self-registration is currently disabled or the caller's
     * status is {@code NOT_PARTICIPATED} (FR-006a), validates every {@link
     * CustomFieldService#registrationFields()} entry's submitted answer against its type/required
     * flag (FR-002, FR-003) with zero Skills required (FR-004), and only then creates a new {@code
     * ACTIVE} record or reactivates an existing non-{@code ACTIVE} one (retaining the existing
     * branch-on-status logic), writing Custom Field values and Skill selections in the same
     * transaction. No partial record is ever visible on rejection.
     */
    public Mono<Participant> submitRegistration(UUID userId, ProfileFormSubmission submission, AuditActor actor) {
        Mono<Participant> chain = organiserSettingsService.current().flatMap(settings -> {
            if (!settings.isSelfRegistrationEnabled()) {
                return Mono.error(new ParticipantConflictException(SELF_REGISTRATION_DISABLED_MESSAGE));
            }
            return participantRepository
                    .findByUserId(userId)
                    .flatMap(existing -> {
                        if (existing.getStatus() == ParticipantStatus.NOT_PARTICIPATED) {
                            return Mono.<Participant>error(new ParticipantConflictException(NOT_PARTICIPATED_MESSAGE));
                        }
                        return validateAndPersist(userId, existing, submission);
                    })
                    .switchIfEmpty(Mono.defer(() -> validateAndPersist(userId, null, submission)))
                    .flatMap(saved -> recordCreated(saved, actor));
        });
        return transactionalOperator.transactional(chain);
    }

    /**
     * A Participant's own self-edit submission (FR-022): same per-field validation as {@link
     * #submitRegistration}, minus the create-vs-reactivate branching (a Participant already
     * exists), gated on {@code selfEditEnabled} and the {@code NOT_PARTICIPATED} lockout — both
     * re-read at call time, never cached (FR-024). Persists changes in place; no new record.
     */
    public Mono<Participant> submitSelfEdit(UUID participantId, ProfileFormSubmission submission, AuditActor actor) {
        Mono<Participant> chain = organiserSettingsService.current().flatMap(settings -> {
            if (!settings.isSelfEditEnabled()) {
                return Mono.error(new ParticipantConflictException(SELF_EDIT_DISABLED_MESSAGE));
            }
            return participantRepository
                    .findById(participantId)
                    .switchIfEmpty(Mono.error(new ParticipantConflictException("Unknown participant: " + participantId)))
                    .flatMap(existing -> {
                        if (existing.getStatus() == ParticipantStatus.NOT_PARTICIPATED) {
                            return Mono.<Participant>error(new ParticipantConflictException(NOT_PARTICIPATED_MESSAGE));
                        }
                        return customFieldService
                                .registrationFields()
                                .collectList()
                                .flatMap(fields -> validateSubmission(fields, submission)
                                        .then(Mono.defer(() -> persistSubmission(existing.getId(), fields, submission)
                                                .thenReturn(existing))));
                    })
                    .flatMap(saved -> recordEdited(saved, actor));
        });
        return transactionalOperator.transactional(chain);
    }

    /**
     * The registration/self-edit form's per-field pre-fill view (FR-006, FR-022): every {@link
     * CustomFieldService#registrationFields()} entry, paired with the caller's currently-stored
     * value if a Participant record already exists (a {@code REVOKED} record's own values, per
     * FR-006), or blank if none does.
     */
    public Mono<List<CustomFieldAnswer>> registrationFieldViewsForUser(UUID userId) {
        return participantRepository
                .findByUserId(userId)
                .flatMap(participant -> registrationFieldViewsForParticipant(participant.getId()))
                .switchIfEmpty(customFieldService
                        .registrationFields()
                        .collectList()
                        .flatMap(customFieldService::blankAnswers));
    }

    /** The self-edit form's per-field pre-fill view for an already-known Participant (FR-022). */
    public Mono<List<CustomFieldAnswer>> registrationFieldViewsForParticipant(UUID participantId) {
        return customFieldService.currentAnswers(participantId);
    }

    /** The registration/self-edit form's Skill pre-fill (a {@code REVOKED} record's own selection). */
    public Mono<List<UUID>> currentSkillIdsForUser(UUID userId) {
        return participantRepository
                .findByUserId(userId)
                .flatMap(participant -> currentSkillIdsForParticipant(participant.getId()))
                .switchIfEmpty(Mono.just(List.of()));
    }

    /** The self-edit form's Skill pre-fill for an already-known Participant (FR-022). */
    public Mono<List<UUID>> currentSkillIdsForParticipant(UUID participantId) {
        return loadSkills(participantId).map(skills -> skills.stream().map(Skill::getId).toList());
    }

    /**
     * Whether {@code GET /register} should show the capacity message instead of the form (FR-010):
     * a read-only check, no advisory lock needed here (races only matter for the write path,
     * gated inside {@link #submitRegistration} instead, research.md §4). A {@code null} {@code
     * maxRegistrations} never blocks (FR-007).
     */
    public Mono<Boolean> isAtRegistrationCapacity() {
        return organiserSettingsService.current().flatMap(settings -> {
            if (settings.getMaxRegistrations() == null) {
                return Mono.just(false);
            }
            return countActiveParticipants().map(count -> count >= settings.getMaxRegistrations());
        });
    }

    private Mono<Long> countActiveParticipants() {
        return databaseClient
                .sql("SELECT count(*) FROM participants WHERE status = 'ACTIVE'")
                .mapValue(Long.class)
                .one();
    }

    private Mono<Participant> validateAndPersist(
            UUID userId, Participant existingOrNull, ProfileFormSubmission submission) {
        // Only a submission that would actually grow the ACTIVE count needs the capacity guard —
        // an already-ACTIVE record's resubmission (a double-click, Edge Cases) is a no-op that
        // must never be blocked by a since-reached cap.
        boolean capacityImpacting =
                existingOrNull == null || existingOrNull.getStatus() != ParticipantStatus.ACTIVE;
        Mono<Void> capacityGuard = capacityImpacting ? acquireLockAndCheckCapacity() : Mono.empty();
        return capacityGuard.then(Mono.defer(() -> customFieldService
                .registrationFields()
                .collectList()
                .flatMap(fields -> validateSubmission(fields, submission)
                        .then(Mono.defer(() -> createOrReactivate(userId, existingOrNull)))
                        .flatMap(participant -> persistSubmission(participant.getId(), fields, submission)
                                .thenReturn(participant)))));
    }

    /**
     * The registration-capacity race guard (FR-009, Edge Cases; research.md §4): a session-scoped
     * Postgres advisory lock — released automatically at transaction end — serializes every
     * concurrent registration attempt through this critical section before {@code COUNT(*)}ing
     * current {@code ACTIVE} Participants against {@code organiserSettings.maxRegistrations}. A
     * {@code null} max never blocks (FR-007).
     */
    private Mono<Void> acquireLockAndCheckCapacity() {
        return databaseClient
                .sql("SELECT pg_advisory_xact_lock(hashtext('participant-registration-cap'))")
                .then()
                .then(Mono.defer(() -> organiserSettingsService.current().flatMap(settings -> {
                    if (settings.getMaxRegistrations() == null) {
                        return Mono.<Void>empty();
                    }
                    return countActiveParticipants().flatMap(count -> {
                        if (count >= settings.getMaxRegistrations()) {
                            return Mono.<Void>error(
                                    new RegistrationCapacityReachedException("Maximum registrations reached"));
                        }
                        return Mono.<Void>empty();
                    });
                })));
    }

    private Mono<Participant> createOrReactivate(UUID userId, Participant existingOrNull) {
        if (existingOrNull == null) {
            return participantRepository.save(newParticipant(userId));
        }
        if (existingOrNull.getStatus() == ParticipantStatus.ACTIVE) {
            return Mono.just(existingOrNull);
        }
        existingOrNull.setStatus(ParticipantStatus.ACTIVE);
        existingOrNull.setUpdatedAt(Instant.now());
        return participantRepository.save(existingOrNull);
    }

    private Mono<Void> validateSubmission(List<CustomFieldDefinition> fields, ProfileFormSubmission submission) {
        return Flux.fromIterable(fields)
                .concatMap(definition -> validateField(
                        definition, submission.answers().get(definition.getId())))
                .then();
    }

    private Mono<Void> validateField(CustomFieldDefinition definition, FieldAnswer answer) {
        return switch (definition.getFieldType()) {
            case FREE_TEXT -> validateFreeText(definition, answer);
            case COUNTRY -> validateCountry(definition, answer);
            case SINGLE_SELECT -> validateSelect(definition, answer, 1);
            case MULTI_SELECT -> validateSelect(definition, answer, Integer.MAX_VALUE);
        };
    }

    private Mono<Void> validateFreeText(CustomFieldDefinition definition, FieldAnswer answer) {
        String value = (answer instanceof FreeText freeText) ? freeText.value() : null;
        if (definition.isRequired() && (value == null || value.isBlank())) {
            return Mono.error(new ParticipantConflictException(definition.getLabel() + " is required"));
        }
        return Mono.empty();
    }

    private Mono<Void> validateCountry(CustomFieldDefinition definition, FieldAnswer answer) {
        String code = (answer instanceof FreeText freeText) ? freeText.value() : null;
        boolean blank = code == null || code.isBlank();
        if (definition.isRequired() && blank) {
            return Mono.error(new ParticipantConflictException(definition.getLabel() + " is required"));
        }
        if (!blank && IsoCountryCatalog.all().stream().noneMatch(country -> country.code().equals(code))) {
            return Mono.error(new ParticipantConflictException("Invalid country: " + code));
        }
        return Mono.empty();
    }

    private Mono<Void> validateSelect(CustomFieldDefinition definition, FieldAnswer answer, int maxAllowed) {
        Set<UUID> optionIds = (answer instanceof Options options) ? options.optionIds() : Set.of();
        if (definition.isRequired() && optionIds.isEmpty()) {
            return Mono.error(new ParticipantConflictException(definition.getLabel() + " is required"));
        }
        if (optionIds.size() > maxAllowed) {
            return Mono.error(
                    new ParticipantConflictException(definition.getLabel() + " allows only one selection"));
        }
        if (optionIds.isEmpty()) {
            return Mono.empty();
        }
        return customFieldOptionRepository
                .findByCustomFieldDefinitionId(definition.getId())
                .map(CustomFieldOption::getId)
                .collect(java.util.stream.Collectors.toSet())
                .flatMap(validOptionIds -> {
                    if (!validOptionIds.containsAll(optionIds)) {
                        return Mono.<Void>error(new ParticipantConflictException(
                                "One or more selected options do not belong to " + definition.getLabel()));
                    }
                    return Mono.empty();
                });
    }

    private Mono<Void> persistSubmission(
            UUID participantId, List<CustomFieldDefinition> fields, ProfileFormSubmission submission) {
        return Flux.fromIterable(fields)
                .concatMap(definition -> persistFieldAnswer(
                        participantId, definition, submission.answers().get(definition.getId())))
                .then(Mono.defer(() -> replaceParticipantSkills(participantId, distinct(submission.skillIds()))));
    }

    private Mono<Void> persistFieldAnswer(UUID participantId, CustomFieldDefinition definition, FieldAnswer answer) {
        return switch (definition.getFieldType()) {
            case FREE_TEXT, COUNTRY -> {
                String value = (answer instanceof FreeText freeText) ? freeText.value() : null;
                String stored = (value == null || value.isBlank()) ? null : value;
                yield upsertFreeTextValue(participantId, definition.getId(), stored)
                        .then(Mono.defer(() -> clearSelectedOptions(participantId, definition.getId())));
            }
            case SINGLE_SELECT, MULTI_SELECT -> {
                Set<UUID> optionIds = (answer instanceof Options options) ? options.optionIds() : Set.of();
                yield upsertMultiSelectValue(participantId, definition.getId())
                        .then(Mono.defer(
                                () -> replaceSelectedOptions(participantId, definition.getId(), optionIds)));
            }
        };
    }

    /** Users with no Participant record yet — the pool the registration form picks from (FR-006a). */
    public Flux<User> findUsersWithoutParticipant() {
        return userRepository
                .findAll()
                .filterWhen(user -> participantRepository
                        .findByUserId(user.getId())
                        .hasElement()
                        .map(hasParticipant -> !hasParticipant));
    }

    // --- Status (FR-007) -------------------------------------------------------------------------

    /**
     * Sets a Participant's status to any of the three {@link ParticipantStatus} values (FR-007).
     * Completes empty if no Participant exists with the given id.
     */
    public Mono<Participant> changeStatus(UUID id, ParticipantStatus status, AuditActor actor) {
        return participantRepository.findById(id).flatMap(participant -> {
            ParticipantStatus oldStatus = participant.getStatus();
            participant.setStatus(status);
            participant.setUpdatedAt(Instant.now());
            return participantRepository
                    .save(participant)
                    .flatMap(saved -> recordStatusChanged(saved, actor, oldStatus, status));
        });
    }

    // --- Skill selections (FR-009) ----------------------------------------------------------------

    public Flux<Skill> allSkills() {
        return skillRepository.findAll();
    }

    /**
     * Replaces a Participant's Skill selection set against {@code participant_skills} (FR-009).
     * Completes empty if the Participant or any of the given {@code skillIds} is unknown, per
     * contracts/participant-management.md's single 404 for either case.
     */
    public Mono<Participant> replaceSkills(UUID participantId, List<UUID> skillIds, AuditActor actor) {
        List<UUID> ids = distinct(skillIds);
        return participantRepository.findById(participantId).flatMap(participant -> allSkillIdsExist(ids)
                .flatMap(allExist -> {
                    if (!allExist) {
                        return Mono.empty();
                    }
                    return replaceParticipantSkills(participantId, ids)
                            .thenReturn(participant)
                            .flatMap(saved -> recordEdited(saved, actor));
                }));
    }

    private Mono<Boolean> allSkillIdsExist(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Mono.just(true);
        }
        Set<UUID> requested = new HashSet<>(ids);
        return skillRepository
                .findAllById(requested)
                .map(Skill::getId)
                .collect(java.util.stream.Collectors.toSet())
                .map(found -> found.size() == requested.size());
    }

    private Mono<Void> replaceParticipantSkills(UUID participantId, List<UUID> skillIds) {
        return databaseClient
                .sql("DELETE FROM participant_skills WHERE participant_id = :pid")
                .bind("pid", participantId)
                .then()
                .thenMany(Flux.fromIterable(skillIds))
                .concatMap(skillId -> databaseClient
                        .sql("INSERT INTO participant_skills (participant_id, skill_id) VALUES (:pid, :sid)")
                        .bind("pid", participantId)
                        .bind("sid", skillId)
                        .then())
                .then();
    }

    // --- Custom Field values (FR-013, FR-014) -----------------------------------------------------

    /**
     * Sets/updates a single Custom Field value for a Participant, validating that the submitted
     * shape matches the field's configured type (FR-014): a {@code FREE_TEXT} field takes
     * {@code freeTextValue} and no {@code optionIds}; a {@code MULTI_SELECT} field takes
     * {@code optionIds} — each of which MUST belong to that same field's own options — and no
     * {@code freeTextValue}. Completes empty if the Participant or the Custom Field Definition is
     * unknown; fails with {@link ParticipantConflictException} for a shape/option mismatch.
     */
    public Mono<Participant> setCustomFieldValue(
            UUID participantId, UUID fieldId, String freeTextValue, List<UUID> optionIds, AuditActor actor) {
        return participantRepository
                .findById(participantId)
                .flatMap(participant -> customFieldDefinitionRepository
                        .findById(fieldId)
                        .flatMap(definition -> validateAndPersistValue(
                                        participantId, definition, freeTextValue, optionIds)
                                .thenReturn(participant)
                                .flatMap(saved -> recordEdited(saved, actor))));
    }

    private Mono<Void> validateAndPersistValue(
            UUID participantId, CustomFieldDefinition definition, String freeTextValue, List<UUID> optionIds) {
        List<UUID> ids = optionIds == null ? List.of() : optionIds;
        if (definition.getFieldType() == CustomFieldType.FREE_TEXT) {
            if (!ids.isEmpty()) {
                return Mono.error(new ParticipantConflictException(
                        "This custom field expects a free-text value, not selected options"));
            }
            return upsertFreeTextValue(participantId, definition.getId(), freeTextValue)
                    .then(Mono.defer(() -> clearSelectedOptions(participantId, definition.getId())));
        }

        // MULTI_SELECT
        if (freeTextValue != null && !freeTextValue.isBlank()) {
            return Mono.error(new ParticipantConflictException(
                    "This custom field expects selected options, not a free-text value"));
        }
        Set<UUID> distinctIds = new LinkedHashSet<>(ids);
        // Each subsequent write is wrapped in Mono.defer so its DatabaseClient call is only ever
        // constructed after the previous step in the chain actually completes — otherwise Java's
        // eager method-argument evaluation would call databaseClient.sql(...) to build every one
        // of these Monos up front, even when validateOptionsBelongToDefinition is about to reject
        // the request and none of them should ever run.
        return validateOptionsBelongToDefinition(definition.getId(), distinctIds)
                .then(Mono.defer(() -> upsertMultiSelectValue(participantId, definition.getId())))
                .then(Mono.defer(
                        () -> replaceSelectedOptions(participantId, definition.getId(), distinctIds)));
    }

    private Mono<Void> validateOptionsBelongToDefinition(UUID definitionId, Set<UUID> optionIds) {
        if (optionIds.isEmpty()) {
            return Mono.empty();
        }
        return customFieldOptionRepository
                .findByCustomFieldDefinitionId(definitionId)
                .map(CustomFieldOption::getId)
                .collect(java.util.stream.Collectors.toSet())
                .flatMap(validOptionIds -> {
                    if (!validOptionIds.containsAll(optionIds)) {
                        return Mono.<Void>error(new ParticipantConflictException(
                                "One or more selected options do not belong to this custom field"));
                    }
                    return Mono.empty();
                });
    }

    private Mono<Void> upsertFreeTextValue(UUID participantId, UUID definitionId, String value) {
        Instant now = Instant.now();
        DatabaseClient.GenericExecuteSpec spec = databaseClient
                .sql(
                        "INSERT INTO custom_field_values"
                                + " (participant_id, custom_field_definition_id, free_text_value, created_at, updated_at)"
                                + " VALUES (:pid, :fid, :value, :now, :now)"
                                + " ON CONFLICT (participant_id, custom_field_definition_id)"
                                + " DO UPDATE SET free_text_value = EXCLUDED.free_text_value, updated_at = EXCLUDED.updated_at")
                .bind("pid", participantId)
                .bind("fid", definitionId)
                .bind("now", now);
        spec = value == null ? spec.bindNull("value", String.class) : spec.bind("value", value);
        return spec.then();
    }

    private Mono<Void> upsertMultiSelectValue(UUID participantId, UUID definitionId) {
        Instant now = Instant.now();
        return databaseClient
                .sql(
                        "INSERT INTO custom_field_values"
                                + " (participant_id, custom_field_definition_id, free_text_value, created_at, updated_at)"
                                + " VALUES (:pid, :fid, NULL, :now, :now)"
                                + " ON CONFLICT (participant_id, custom_field_definition_id)"
                                + " DO UPDATE SET free_text_value = NULL, updated_at = EXCLUDED.updated_at")
                .bind("pid", participantId)
                .bind("fid", definitionId)
                .bind("now", now)
                .then();
    }

    private Mono<Void> clearSelectedOptions(UUID participantId, UUID definitionId) {
        return databaseClient
                .sql(
                        "DELETE FROM custom_field_value_options"
                                + " WHERE participant_id = :pid AND custom_field_definition_id = :fid")
                .bind("pid", participantId)
                .bind("fid", definitionId)
                .then();
    }

    private Mono<Void> replaceSelectedOptions(UUID participantId, UUID definitionId, Set<UUID> optionIds) {
        return clearSelectedOptions(participantId, definitionId)
                .thenMany(Flux.fromIterable(optionIds))
                .concatMap(optionId -> databaseClient
                        .sql(
                                "INSERT INTO custom_field_value_options"
                                        + " (participant_id, custom_field_definition_id, custom_field_option_id)"
                                        + " VALUES (:pid, :fid, :oid)")
                        .bind("pid", participantId)
                        .bind("fid", definitionId)
                        .bind("oid", optionId)
                        .then())
                .then();
    }

    // --- Incomplete computation (FR-027) -----------------------------------------------------------

    /**
     * True iff any {@code custom_field_definitions} row has {@code required = true} with no
     * corresponding {@code custom_field_values} row for this Participant (FR-027). Computed at
     * read time so it can never go stale relative to field-definition changes.
     */
    public Mono<Boolean> isIncomplete(UUID participantId) {
        return databaseClient
                .sql(
                        "SELECT count(*) FROM custom_field_definitions d"
                                + " WHERE d.required = true"
                                + " AND NOT EXISTS ("
                                + "   SELECT 1 FROM custom_field_values v"
                                + "   WHERE v.custom_field_definition_id = d.id AND v.participant_id = :pid)")
                .bind("pid", participantId)
                .mapValue(Long.class)
                .one()
                .map(count -> count > 0);
    }

    // --- Read views ----------------------------------------------------------------------------

    public Flux<ParticipantSummary> findAllSummaries() {
        return participantRepository
                .findAll()
                .concatMap(participant -> userRepository
                        .findById(participant.getUserId())
                        .flatMap(user -> Mono.zip(
                                        isIncomplete(participant.getId()),
                                        groupService
                                                .findActiveGroupForParticipant(participant.getId())
                                                .hasElement())
                                .map(tuple -> new ParticipantSummary(
                                        participant.getId(),
                                        participant.getUserId(),
                                        user.getDisplayName(),
                                        user.getEmail(),
                                        participant.getStatus(),
                                        tuple.getT1(),
                                        tuple.getT2()))));
    }

    // --- Delete ------------------------------------------------------------------------------------

    /**
     * Deletes a Participant record — the Participant only, never the underlying {@link
     * net.fabcelhaft.hackathonorganiser.user.User} account. Rejects with a friendly {@link
     * ParticipantConflictException} if the Participant currently belongs to an active Group (it
     * must be removed from the Group first, {@link GroupService#findActiveGroupForParticipant}); a
     * no-op for an unknown {@code id}, matching {@code SkillService.delete}'s convention. Cleans up
     * every child row referencing this Participant first — {@code custom_field_value_options},
     * {@code custom_field_values}, {@code participant_skills}, and any historical (inactive)
     * {@code group_members} rows — since none of those foreign keys cascade (schema.sql).
     */
    public Mono<Void> delete(UUID id, AuditActor actor) {
        Mono<Void> chain = participantRepository.findById(id).flatMap(participant -> groupService
                .findActiveGroupForParticipant(id)
                .hasElement()
                .flatMap(inGroup -> {
                    if (inGroup) {
                        return Mono.<Void>error(new ParticipantConflictException(
                                "Cannot delete this participant: still a member of a Group"));
                    }
                    return recordDeleted(participant, actor)
                            .then(deleteAssociatedData(id))
                            .then(Mono.defer(() -> participantRepository.deleteById(id)));
                }));
        return transactionalOperator.transactional(chain);
    }

    private Mono<Void> recordDeleted(Participant participant, AuditActor actor) {
        return userDisplayName(participant.getUserId())
                .flatMap(name -> auditService.record(
                        AuditEventType.DELETED,
                        actor,
                        AuditSubjectType.PARTICIPANT,
                        participant.getId(),
                        name,
                        null,
                        null,
                        null))
                .then();
    }

    private Mono<Void> deleteAssociatedData(UUID participantId) {
        return databaseClient
                .sql("DELETE FROM custom_field_value_options WHERE participant_id = :pid")
                .bind("pid", participantId)
                .then()
                .then(Mono.defer(() -> databaseClient
                        .sql("DELETE FROM custom_field_values WHERE participant_id = :pid")
                        .bind("pid", participantId)
                        .then()))
                .then(Mono.defer(() -> databaseClient
                        .sql("DELETE FROM participant_skills WHERE participant_id = :pid")
                        .bind("pid", participantId)
                        .then()))
                .then(Mono.defer(() -> databaseClient
                        .sql("DELETE FROM group_members WHERE participant_id = :pid")
                        .bind("pid", participantId)
                        .then()));
    }

    public Mono<ParticipantDetail> findDetail(UUID participantId) {
        return participantRepository
                .findById(participantId)
                .flatMap(participant -> userRepository
                        .findById(participant.getUserId())
                        .flatMap(user -> loadSkills(participantId)
                                .flatMap(skills -> loadCustomFieldValueViews(participantId)
                                        .flatMap(cfViews -> isIncomplete(participantId)
                                                .map(incomplete -> new ParticipantDetail(
                                                        participant,
                                                        user.getDisplayName(),
                                                        user.getEmail(),
                                                        skills,
                                                        skills.stream().map(Skill::getId).toList(),
                                                        incomplete,
                                                        cfViews))))));
    }

    private Mono<List<Skill>> loadSkills(UUID participantId) {
        return databaseClient
                .sql("SELECT skill_id FROM participant_skills WHERE participant_id = :pid")
                .bind("pid", participantId)
                .mapValue(UUID.class)
                .all()
                .collectList()
                .flatMap(ids -> ids.isEmpty()
                        ? Mono.just(List.<Skill>of())
                        : skillRepository.findAllById(ids).collectList());
    }

    private Mono<List<CustomFieldAnswer>> loadCustomFieldValueViews(UUID participantId) {
        return customFieldDefinitionRepository
                .findAll()
                .collectList()
                .flatMap(fields -> customFieldService.answersFor(participantId, fields));
    }

    // --- Participants directory & detail-for-viewer (FR-017, FR-018, FR-019, FR-025-FR-031) -----

    /**
     * The Participants directory table's read model (FR-027): only {@code ACTIVE} Participants,
     * ordered alphabetically ascending by display name (FR-027a), one value per
     * {@code overview = true} Custom Field Definition (FR-027) — never Skills (FR-027).
     */
    public Flux<DirectoryRow> findDirectoryListing() {
        return customFieldDefinitionRepository
                .findAll()
                .filter(CustomFieldDefinition::isOverview)
                .collectList()
                .flatMapMany(overviewFields -> participantRepository
                        .findAll()
                        .filter(participant -> participant.getStatus() == ParticipantStatus.ACTIVE)
                        .concatMap(participant -> userRepository
                                .findById(participant.getUserId())
                                .flatMap(user -> customFieldService
                                        .answersFor(participant.getId(), overviewFields)
                                        .map(views ->
                                                new DirectoryRow(participant.getId(), user.getDisplayName(), views)))))
                .sort(java.util.Comparator.comparing(DirectoryRow::displayName, String.CASE_INSENSITIVE_ORDER));
    }

    /**
     * A Participant's detail view resolved for one specific viewer (data-model.md; FR-017, FR-018,
     * FR-019, FR-029, FR-030): self/organiser see every field and Skill regardless of {@code
     * public}/{@code overview}/skill-visibility flags; any other viewer sees only {@code public =
     * true} Custom Field values (an Overview-only, non-Public field is omitted entirely, FR-017)
     * and Skills only when {@code organiserSettings.skillVisibilityEnabled} is currently {@code
     * true} (FR-018, FR-019). Completes empty (404, per the controller) if no Participant exists
     * with this id, or — for any viewer other than the Participant themselves — if its status
     * isn't {@code ACTIVE} (a non-organiser detail route never serves a non-{@code ACTIVE} record;
     * an Organiser views those via 002's existing organiser participant-management screens
     * instead).
     */
    public Mono<ParticipantViewerDetail> findDetailForViewer(
            UUID participantId, UUID viewerUserId, boolean viewerIsOrganiser) {
        return participantRepository.findById(participantId).flatMap(participant -> userRepository
                .findById(participant.getUserId())
                .flatMap(user -> {
                    boolean isSelf = user.getId().equals(viewerUserId);
                    if (!isSelf && participant.getStatus() != ParticipantStatus.ACTIVE) {
                        return Mono.empty();
                    }
                    boolean fullAccess = isSelf || viewerIsOrganiser;
                    return Mono.zip(
                                    loadCustomFieldValueViews(participantId),
                                    loadSkills(participantId),
                                    organiserSettingsService.current())
                            .map(tuple -> {
                                List<CustomFieldAnswer> allViews = tuple.getT1();
                                List<Skill> allSkills = tuple.getT2();
                                boolean skillsVisibleToOthers = tuple.getT3().isSkillVisibilityEnabled();
                                boolean skillsVisibleToViewer = fullAccess || skillsVisibleToOthers;
                                List<ViewerFieldValue> fields = allViews.stream()
                                        .map(view -> {
                                            boolean visibleToOthers = view.definition().isPublic_();
                                            boolean visibleToViewer = fullAccess || visibleToOthers;
                                            return new ViewerFieldValue(
                                                    view.definition(),
                                                    view.options(),
                                                    view.freeTextValue(),
                                                    view.selectedOptionIds(),
                                                    visibleToViewer,
                                                    visibleToOthers);
                                        })
                                        .filter(ViewerFieldValue::visibleToViewer)
                                        .toList();
                                return new ParticipantViewerDetail(
                                        participant,
                                        user.getDisplayName(),
                                        user.getEmail(),
                                        fields,
                                        skillsVisibleToViewer ? allSkills : List.of(),
                                        skillsVisibleToViewer,
                                        skillsVisibleToOthers,
                                        isSelf,
                                        viewerIsOrganiser);
                            });
                }));
    }

    private static List<UUID> distinct(List<UUID> ids) {
        return ids == null ? List.of() : ids.stream().distinct().toList();
    }

    private Participant newParticipant(UUID userId) {
        Participant participant = new Participant();
        participant.setUserId(userId);
        participant.setStatus(ParticipantStatus.ACTIVE);
        Instant now = Instant.now();
        participant.setCreatedAt(now);
        participant.setUpdatedAt(now);
        return participant;
    }

    // --- Read-model view types -------------------------------------------------------------------

    public record ParticipantSummary(
            UUID id,
            UUID userId,
            String userDisplayName,
            String userEmail,
            ParticipantStatus status,
            boolean incomplete,
            boolean inGroup) {}

    public record ParticipantDetail(
            Participant participant,
            String userDisplayName,
            String userEmail,
            List<Skill> skills,
            List<UUID> skillIds,
            boolean incomplete,
            List<CustomFieldAnswer> customFieldValues) {}

    /** One Participants directory table row — Overview-marked field values only (FR-027). */
    public record DirectoryRow(UUID participantId, String displayName, List<CustomFieldAnswer> overviewValues) {}

    /**
     * One Custom Field value already resolved for a specific viewer (FR-017, FR-020): {@code
     * visibleToViewer} is what the template uses to decide whether to render this row at all in
     * non-self, non-organiser mode (already {@code true} for every entry in self/organiser mode);
     * {@code visibleToOthers} — {@code definition.public}, viewer-independent — drives FR-020's
     * "visible to others"/"private" self-view label.
     */
    public record ViewerFieldValue(
            CustomFieldDefinition definition,
            List<CustomFieldOption> options,
            String freeTextValue,
            List<UUID> selectedOptionIds,
            boolean visibleToViewer,
            boolean visibleToOthers) {}

    /**
     * A Participant's detail view resolved for one viewer (data-model.md
     * {@code findDetailForViewer}): {@code fields} already contains only what this viewer may see;
     * {@code skills} is empty when not visible to this viewer, with {@code skillsVisibleToViewer}
     * distinguishing "no skills selected" from "hidden from this viewer". {@code
     * skillsVisibleToOthers} — {@code organiserSettings.skillVisibilityEnabled}, viewer-independent
     * — drives FR-020's self-mode "visible to others"/"private" Skills label, mirroring {@code
     * ViewerFieldValue.visibleToOthers}.
     */
    public record ParticipantViewerDetail(
            Participant participant,
            String userDisplayName,
            String userEmail,
            List<ViewerFieldValue> fields,
            List<Skill> skills,
            boolean skillsVisibleToViewer,
            boolean skillsVisibleToOthers,
            boolean self,
            boolean organiserView) {}
}

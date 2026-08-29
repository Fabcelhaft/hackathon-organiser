package net.fabcelhaft.hackathonorganiser.participant;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinition;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinitionRepository;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldOption;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldOptionRepository;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldType;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.skill.Skill;
import net.fabcelhaft.hackathonorganiser.skill.SkillRepository;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
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

    public ParticipantService(
            ParticipantRepository participantRepository,
            UserRepository userRepository,
            SkillRepository skillRepository,
            CustomFieldDefinitionRepository customFieldDefinitionRepository,
            CustomFieldOptionRepository customFieldOptionRepository,
            DatabaseClient databaseClient,
            OrganiserSettingsService organiserSettingsService,
            GroupService groupService) {
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.skillRepository = skillRepository;
        this.customFieldDefinitionRepository = customFieldDefinitionRepository;
        this.customFieldOptionRepository = customFieldOptionRepository;
        this.databaseClient = databaseClient;
        this.organiserSettingsService = organiserSettingsService;
        this.groupService = groupService;
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
                return Mono.error(new ParticipantConflictException("Self-registration is currently disabled"));
            }
            return participantRepository
                    .findByUserId(userId)
                    .flatMap(existing -> {
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
    public Mono<Participant> selfRevoke(UUID participantId) {
        return organiserSettingsService.current().flatMap(settings -> {
            if (!settings.isSelfRevocationEnabled()) {
                return Mono.error(new ParticipantConflictException("Self-revocation is currently disabled"));
            }
            return participantRepository.findById(participantId).flatMap(participant -> {
                participant.setStatus(ParticipantStatus.REVOKED);
                participant.setUpdatedAt(Instant.now());
                return participantRepository
                        .save(participant)
                        .flatMap(saved -> groupService
                                .findActiveGroupForParticipant(participantId)
                                .flatMap(group -> groupService.removeMember(group.getId(), participantId))
                                .thenReturn(saved));
            });
        });
    }

    // --- Registration (FR-006a, FR-006b) --------------------------------------------------------

    /**
     * Registers a User as a Participant with initial {@code status = ACTIVE} (FR-006b), rejecting
     * an unknown {@code userId} or a User who already has a Participant record (FR-006a) with a
     * friendly {@link ParticipantConflictException}.
     */
    public Mono<Participant> register(UUID userId) {
        return userRepository.existsById(userId).flatMap(userExists -> {
            if (!userExists) {
                return Mono.error(new ParticipantConflictException("Unknown user: " + userId));
            }
            return participantRepository
                    .findByUserId(userId)
                    .<Participant>flatMap(existing -> Mono.error(new ParticipantConflictException(
                            "This user is already registered as a Participant")))
                    .switchIfEmpty(Mono.defer(() -> participantRepository.save(newParticipant(userId))));
        });
    }

    /** The current user's own Participant record, if any — used by the homepage (FR-001, FR-007a). */
    public Mono<Participant> findByUserId(UUID userId) {
        return participantRepository.findByUserId(userId);
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
    public Mono<Participant> changeStatus(UUID id, ParticipantStatus status) {
        return participantRepository.findById(id).flatMap(participant -> {
            participant.setStatus(status);
            participant.setUpdatedAt(Instant.now());
            return participantRepository.save(participant);
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
    public Mono<Participant> replaceSkills(UUID participantId, List<UUID> skillIds) {
        List<UUID> ids = distinct(skillIds);
        return participantRepository.findById(participantId).flatMap(participant -> allSkillIdsExist(ids)
                .flatMap(allExist -> {
                    if (!allExist) {
                        return Mono.empty();
                    }
                    return replaceParticipantSkills(participantId, ids).thenReturn(participant);
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
            UUID participantId, UUID fieldId, String freeTextValue, List<UUID> optionIds) {
        return participantRepository
                .findById(participantId)
                .flatMap(participant -> customFieldDefinitionRepository
                        .findById(fieldId)
                        .flatMap(definition -> validateAndPersistValue(
                                        participantId, definition, freeTextValue, optionIds)
                                .thenReturn(participant)));
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
                        .flatMap(user -> isIncomplete(participant.getId())
                                .map(incomplete -> new ParticipantSummary(
                                        participant.getId(),
                                        participant.getUserId(),
                                        user.getDisplayName(),
                                        participant.getStatus(),
                                        incomplete))));
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

    private Mono<List<CustomFieldValueView>> loadCustomFieldValueViews(UUID participantId) {
        return customFieldDefinitionRepository
                .findAll()
                .concatMap(definition -> Mono.zip(
                                customFieldOptionRepository
                                        .findByCustomFieldDefinitionId(definition.getId())
                                        .collectList(),
                                loadFreeTextValue(participantId, definition.getId()),
                                loadSelectedOptionIds(participantId, definition.getId()))
                        .map(tuple -> new CustomFieldValueView(
                                definition, tuple.getT1(), tuple.getT2(), tuple.getT3())))
                .collectList();
    }

    private Mono<String> loadFreeTextValue(UUID participantId, UUID definitionId) {
        return databaseClient
                .sql(
                        "SELECT free_text_value FROM custom_field_values"
                                + " WHERE participant_id = :pid AND custom_field_definition_id = :fid"
                                + " AND free_text_value IS NOT NULL")
                .bind("pid", participantId)
                .bind("fid", definitionId)
                .map(row -> row.get("free_text_value", String.class))
                .one()
                .defaultIfEmpty("");
    }

    private Mono<List<UUID>> loadSelectedOptionIds(UUID participantId, UUID definitionId) {
        return databaseClient
                .sql(
                        "SELECT custom_field_option_id FROM custom_field_value_options"
                                + " WHERE participant_id = :pid AND custom_field_definition_id = :fid")
                .bind("pid", participantId)
                .bind("fid", definitionId)
                .map(row -> row.get("custom_field_option_id", UUID.class))
                .all()
                .collectList();
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
            UUID id, UUID userId, String userDisplayName, ParticipantStatus status, boolean incomplete) {}

    public record CustomFieldValueView(
            CustomFieldDefinition definition,
            List<CustomFieldOption> options,
            String freeTextValue,
            List<UUID> selectedOptionIds) {}

    public record ParticipantDetail(
            Participant participant,
            String userDisplayName,
            String userEmail,
            List<Skill> skills,
            List<UUID> skillIds,
            boolean incomplete,
            List<CustomFieldValueView> customFieldValues) {}
}

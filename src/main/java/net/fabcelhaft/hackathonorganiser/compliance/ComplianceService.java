package net.fabcelhaft.hackathonorganiser.compliance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinition;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinitionRepository;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldType;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Evaluates a Group's Compliance status against the current instance-wide Compliance Ruleset
 * (data-model.md "Compliance Status"; research.md §1, §5; FR-012, FR-012a, FR-014) and manages the
 * Custom Field Diversity Requirement collection that ruleset's optional rules are drawn from
 * (FR-011, FR-011d).
 *
 * <p>{@link #evaluate} is computed fresh on every call — no caching, no persisted status column —
 * so a settings or membership change takes effect on the very next read (research.md §5), exactly
 * like 003/004's existing toggles.
 */
@Service
public class ComplianceService {

    private final OrganiserSettingsService organiserSettingsService;
    private final ComplianceDiversityRequirementRepository requirementRepository;
    private final CustomFieldDefinitionRepository customFieldDefinitionRepository;
    private final DatabaseClient databaseClient;

    public ComplianceService(
            OrganiserSettingsService organiserSettingsService,
            ComplianceDiversityRequirementRepository requirementRepository,
            CustomFieldDefinitionRepository customFieldDefinitionRepository,
            DatabaseClient databaseClient) {
        this.organiserSettingsService = organiserSettingsService;
        this.requirementRepository = requirementRepository;
        this.customFieldDefinitionRepository = customFieldDefinitionRepository;
        this.databaseClient = databaseClient;
    }

    /**
     * {@code COMPLIANT_OVERRIDE} if {@code group.complianceOverride} is set — short-circuiting
     * every other check (FR-015); otherwise {@code COMPLIANT} iff {@code
     * memberParticipantIds.size()} is at or below the configured Maximum Group Members (inclusive,
     * research.md §1), at or above the configured Minimum Group Members when one is set, and every
     * configured {@link ComplianceDiversityRequirement} has at least its {@code
     * minimumDistinctValues} distinct non-blank recorded values for its Custom Field across the
     * given members — else {@code NOT_COMPLIANT}. "No Group Yet" is not a value this method ever
     * returns: callers branch on that case themselves when no {@link Group} exists to evaluate.
     */
    public Mono<ComplianceStatus> evaluate(Group group, List<UUID> memberParticipantIds) {
        if (group.isComplianceOverride()) {
            return Mono.just(ComplianceStatus.COMPLIANT_OVERRIDE);
        }
        return organiserSettingsService.current().flatMap(settings -> {
            int size = memberParticipantIds.size();
            boolean withinMaximum = size <= settings.getMaxGroupMembers();
            boolean meetsMinimum =
                    settings.getMinGroupMembers() == null || size >= settings.getMinGroupMembers();
            if (!withinMaximum || !meetsMinimum) {
                return Mono.just(ComplianceStatus.NOT_COMPLIANT);
            }
            return requirementRepository
                    .findAll()
                    .concatMap(requirement -> isSatisfied(requirement, memberParticipantIds))
                    .all(Boolean::booleanValue)
                    .map(satisfied -> satisfied ? ComplianceStatus.COMPLIANT : ComplianceStatus.NOT_COMPLIANT);
        });
    }

    // --- Requirement CRUD (FR-011, FR-011d, FR-019) -----------------------------------------------

    public Flux<ComplianceDiversityRequirement> findAllRequirements() {
        return requirementRepository.findAll();
    }

    /**
     * Adds a diversity requirement, rejecting a minimum below 2 (FR-011d), an unknown {@code
     * customFieldDefinitionId}, or one already configured (research.md §3, at most one requirement
     * per field) with a friendly {@link ComplianceConflictException}.
     */
    public Mono<ComplianceDiversityRequirement> addRequirement(UUID customFieldDefinitionId, int minimumDistinctValues) {
        if (minimumDistinctValues < 2) {
            return Mono.error(
                    new ComplianceConflictException("A diversity requirement needs at least 2 distinct values"));
        }
        if (customFieldDefinitionId == null) {
            return Mono.error(new ComplianceConflictException("A custom field must be selected"));
        }
        return customFieldDefinitionRepository
                .existsById(customFieldDefinitionId)
                .flatMap(exists -> {
                    if (!exists) {
                        return Mono.error(
                                new ComplianceConflictException("Unknown custom field: " + customFieldDefinitionId));
                    }
                    return requirementRepository
                            .existsByCustomFieldDefinitionId(customFieldDefinitionId)
                            .flatMap(alreadyConfigured -> {
                                if (alreadyConfigured) {
                                    return Mono.error(new ComplianceConflictException(
                                            "This custom field already has a diversity requirement configured"));
                                }
                                Instant now = Instant.now();
                                ComplianceDiversityRequirement requirement = new ComplianceDiversityRequirement();
                                requirement.setCustomFieldDefinitionId(customFieldDefinitionId);
                                requirement.setMinimumDistinctValues(minimumDistinctValues);
                                requirement.setCreatedAt(now);
                                requirement.setUpdatedAt(now);
                                return requirementRepository.save(requirement);
                            });
                });
    }

    /** Removes a diversity requirement — always succeeds for an existing row (no further guard needed). */
    public Mono<Void> removeRequirement(UUID id) {
        return requirementRepository.deleteById(id);
    }

    // --- private evaluation helpers ----------------------------------------------------------------

    private Mono<Boolean> isSatisfied(ComplianceDiversityRequirement requirement, List<UUID> memberParticipantIds) {
        return customFieldDefinitionRepository
                .findById(requirement.getCustomFieldDefinitionId())
                .flatMap(definition -> countDistinctValues(definition, memberParticipantIds))
                .defaultIfEmpty(0L)
                .map(distinctCount -> distinctCount >= requirement.getMinimumDistinctValues());
    }

    /**
     * Counts distinct non-blank recorded values for one Custom Field across the given members —
     * {@code custom_field_value_options} for a {@code MULTI_SELECT}/{@code SINGLE_SELECT} field,
     * {@code custom_field_values.free_text_value} otherwise (FREE_TEXT and COUNTRY both store into
     * that same column, mirroring how {@code ParticipantService}/{@code CustomFieldService} already
     * treat those two types identically for value storage). Binding {@code memberParticipantIds} as
     * a native Postgres array lets the {@code = ANY(:pids)} filter run in one query rather than one
     * per member.
     */
    private Mono<Long> countDistinctValues(CustomFieldDefinition definition, List<UUID> memberParticipantIds) {
        if (memberParticipantIds.isEmpty()) {
            return Mono.just(0L);
        }
        UUID[] ids = memberParticipantIds.toArray(new UUID[0]);
        boolean isSelectType = definition.getFieldType() == CustomFieldType.MULTI_SELECT
                || definition.getFieldType() == CustomFieldType.SINGLE_SELECT;
        String sql = isSelectType
                ? "SELECT count(DISTINCT custom_field_option_id) FROM custom_field_value_options"
                        + " WHERE custom_field_definition_id = :fid AND participant_id = ANY(:pids)"
                : "SELECT count(DISTINCT free_text_value) FROM custom_field_values"
                        + " WHERE custom_field_definition_id = :fid AND participant_id = ANY(:pids)"
                        + " AND free_text_value IS NOT NULL AND btrim(free_text_value) <> ''";
        return databaseClient
                .sql(sql)
                .bind("fid", definition.getId())
                .bind("pids", ids)
                .mapValue(Long.class)
                .one();
    }
}

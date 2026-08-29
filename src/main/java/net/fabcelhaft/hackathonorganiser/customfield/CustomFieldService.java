package net.fabcelhaft.hackathonorganiser.customfield;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.BadSqlGrammarException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Organiser-facing operations on {@link CustomFieldDefinition}/{@link CustomFieldOption} (T029):
 * definition CRUD including the {@code MULTI_SELECT} minimum-option rule (FR-012) and the
 * {@code field_type} lock once a value exists (FR-012a), option CRUD including its own delete
 * guard (FR-012b), and the definition delete guard (FR-023).
 */
@Service
public class CustomFieldService {

    private final CustomFieldDefinitionRepository definitionRepository;
    private final CustomFieldOptionRepository optionRepository;
    private final DatabaseClient databaseClient;

    public CustomFieldService(
            CustomFieldDefinitionRepository definitionRepository,
            CustomFieldOptionRepository optionRepository,
            DatabaseClient databaseClient) {
        this.definitionRepository = definitionRepository;
        this.optionRepository = optionRepository;
        this.databaseClient = databaseClient;
    }

    public Flux<CustomFieldDefinition> findAll() {
        return definitionRepository.findAll();
    }

    /**
     * The fields presented on the registration/self-edit form (data-model.md; FR-002a): every
     * non-{@code COUNTRY} definition, plus the {@code COUNTRY} definition only when its {@code
     * enabled} column is {@code true} (research.md §1).
     */
    public Flux<CustomFieldDefinition> registrationFields() {
        return definitionRepository
                .findAll()
                .filter(definition -> definition.getFieldType() != CustomFieldType.COUNTRY || definition.isEnabled());
    }

    public Mono<CustomFieldDefinition> findById(UUID id) {
        return definitionRepository.findById(id);
    }

    public Flux<CustomFieldOption> findOptions(UUID customFieldDefinitionId) {
        return optionRepository.findByCustomFieldDefinitionId(customFieldDefinitionId);
    }

    /**
     * Reports whether any Participant value already references this definition — used by the
     * edit form to disable the {@code field_type} control (FR-012a) — without exposing the raw
     * count.
     */
    public Mono<Boolean> hasRecordedValues(UUID customFieldDefinitionId) {
        return valueReferenceCount(customFieldDefinitionId).map(count -> count > 0);
    }

    /**
     * Creates a new Custom Field Definition, {@code public}/{@code overview} defaulting unchecked
     * (FR-016). A {@code MULTI_SELECT}/{@code SINGLE_SELECT} definition MUST be submitted with at
     * least one option (FR-012); its initial options are persisted alongside it. {@code field_type
     * = COUNTRY} is rejected outright — that row is seeded once and never created by an Organiser
     * (FR-013, research.md §1).
     */
    public Mono<CustomFieldDefinition> create(
            String label,
            CustomFieldType fieldType,
            boolean required,
            List<String> optionLabels,
            boolean public_,
            boolean overview) {
        if (fieldType == CustomFieldType.COUNTRY) {
            return Mono.error(new CustomFieldConflictException("The Country field cannot be created"));
        }
        List<String> options = optionLabels == null ? List.of() : optionLabels;
        boolean isSelectType =
                fieldType == CustomFieldType.MULTI_SELECT || fieldType == CustomFieldType.SINGLE_SELECT;
        if (isSelectType && options.isEmpty()) {
            return Mono.error(new CustomFieldConflictException(
                    "A single-select or multi-select custom field requires at least one option"));
        }

        Instant now = Instant.now();
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setLabel(label);
        definition.setFieldType(fieldType);
        definition.setRequired(required);
        definition.setPublic_(public_);
        definition.setOverview(overview);
        // Every field type other than COUNTRY has no create/enable distinction — its row existing
        // *is* "enabled" (research.md §1) — so a newly-created definition is always enabled=true
        // regardless of the DB column's own DEFAULT true, since save() issues an explicit INSERT
        // naming every mapped column (a Java boolean field is never left NULL for the DEFAULT to apply).
        definition.setEnabled(true);
        definition.setCreatedAt(now);
        definition.setUpdatedAt(now);

        return definitionRepository.save(definition)
                .flatMap(saved -> {
                    if (!isSelectType || options.isEmpty()) {
                        return Mono.just(saved);
                    }
                    return Flux.fromIterable(options)
                            .concatMap(optionLabel -> saveOption(saved.getId(), optionLabel))
                            .then(Mono.just(saved));
                });
    }

    /**
     * Updates a Custom Field Definition's {@code label}/{@code required} flag, its {@code
     * field_type} only when {@code requestedFieldType} is non-null and actually differs from the
     * current type — in which case FR-012a's lock is enforced: the change is rejected with {@link
     * CustomFieldConflictException} once any Participant value already exists — and its {@code
     * public}/{@code overview} visibility flags (FR-016), each applied independently of the
     * {@code field_type} lock whenever the corresponding argument is non-null. A requested {@code
     * field_type} change on the {@code COUNTRY} row is always rejected — its type is fixed
     * (FR-013, research.md §1). Completes empty if no definition exists with the given id.
     */
    public Mono<CustomFieldDefinition> update(
            UUID id,
            String label,
            boolean required,
            CustomFieldType requestedFieldType,
            Boolean public_,
            Boolean overview) {
        return definitionRepository.findById(id)
                .flatMap(definition -> {
                    boolean typeChangeRequested =
                            requestedFieldType != null && requestedFieldType != definition.getFieldType();
                    if (typeChangeRequested && definition.getFieldType() == CustomFieldType.COUNTRY) {
                        return Mono.error(
                                new CustomFieldConflictException("The Country field's type cannot be changed"));
                    }
                    Mono<Void> guard = typeChangeRequested
                            ? assertNoValuesExist(id)
                            : Mono.<Void>empty();
                    return guard.then(Mono.defer(() -> {
                        definition.setLabel(label);
                        definition.setRequired(required);
                        if (typeChangeRequested) {
                            definition.setFieldType(requestedFieldType);
                        }
                        if (public_ != null) {
                            definition.setPublic_(public_);
                        }
                        if (overview != null) {
                            definition.setOverview(overview);
                        }
                        definition.setUpdatedAt(Instant.now());
                        return definitionRepository.save(definition);
                    }));
                });
    }

    /**
     * Deletes a Custom Field Definition, blocked by {@link CustomFieldConflictException} while
     * any Participant value still references it (FR-023), or outright for the {@code COUNTRY} row
     * — it is never deleted, only enabled/disabled via {@link #setCountryEnabled} (FR-013,
     * research.md §1). Its own options (if any) are removed first so the definition's row can be
     * deleted without a foreign-key violation.
     */
    public Mono<Void> deleteDefinition(UUID id) {
        return definitionRepository.findById(id).flatMap(definition -> {
            if (definition.getFieldType() == CustomFieldType.COUNTRY) {
                return Mono.error(new CustomFieldConflictException(
                        "The Country field cannot be deleted — use Enable/Disable instead"));
            }
            return valueReferenceCount(id).flatMap(count -> {
                if (count > 0) {
                    return Mono.error(new CustomFieldConflictException(
                            "Cannot delete this custom field: still referenced by " + count
                                    + " Participant value(s)"));
                }
                return optionRepository
                        .findByCustomFieldDefinitionId(id)
                        .concatMap(option -> optionRepository.deleteById(option.getId()))
                        .then(definitionRepository.deleteById(id));
            });
        });
    }

    /**
     * Toggles the singleton {@code COUNTRY} definition's {@code enabled} column (FR-013, FR-015):
     * enabling makes it appear on the next-rendered registration/self-edit form as the full ISO
     * 3166 list; disabling removes it from those forms while any already-recorded Participant
     * values remain stored and visible on that Participant's own existing record. Completes empty
     * if no {@code COUNTRY} definition exists (defensive only — it is always seeded).
     */
    public Mono<CustomFieldDefinition> setCountryEnabled(boolean enabled) {
        return definitionRepository
                .findAll()
                .filter(definition -> definition.getFieldType() == CustomFieldType.COUNTRY)
                .next()
                .flatMap(country -> {
                    country.setEnabled(enabled);
                    country.setUpdatedAt(Instant.now());
                    return definitionRepository.save(country);
                });
    }

    /**
     * Adds a selectable option to a {@code MULTI_SELECT} definition, rejecting a label that
     * duplicates an existing option of the same definition case-insensitively. Completes empty if
     * no definition exists with the given id.
     */
    public Mono<CustomFieldOption> addOption(UUID customFieldDefinitionId, String label) {
        return definitionRepository.findById(customFieldDefinitionId)
                .flatMap(definition -> optionRepository
                        .existsByCustomFieldDefinitionIdAndLabelIgnoreCase(customFieldDefinitionId, label)
                        .flatMap(exists -> {
                            if (exists) {
                                return Mono.error(new CustomFieldConflictException(
                                        "An option named '" + label + "' already exists for this custom field"));
                            }
                            return saveOption(customFieldDefinitionId, label);
                        }));
    }

    /**
     * Removes a selectable option, blocked by {@link CustomFieldConflictException} while any
     * Participant value still selects it (FR-012b).
     */
    public Mono<Void> deleteOption(UUID optionId) {
        return optionValueReferenceCount(optionId)
                .flatMap(count -> {
                    if (count > 0) {
                        return Mono.error(new CustomFieldConflictException(
                                "Cannot delete this option: still selected by " + count + " Participant value(s)"));
                    }
                    return optionRepository.deleteById(optionId);
                });
    }

    private Mono<CustomFieldOption> saveOption(UUID customFieldDefinitionId, String label) {
        Instant now = Instant.now();
        CustomFieldOption option = new CustomFieldOption();
        option.setCustomFieldDefinitionId(customFieldDefinitionId);
        option.setLabel(label);
        option.setCreatedAt(now);
        option.setUpdatedAt(now);
        return optionRepository.save(option);
    }

    private Mono<Void> assertNoValuesExist(UUID customFieldDefinitionId) {
        return valueReferenceCount(customFieldDefinitionId)
                .flatMap(count -> count > 0
                        ? Mono.<Void>error(new CustomFieldConflictException(
                                "Cannot change the field type: " + count + " Participant value(s) already recorded"))
                        : Mono.<Void>empty());
    }

    /**
     * FR-012a/FR-023 guards, queried directly against {@code custom_field_values} and
     * {@code custom_field_value_options} — the real table names User Story 3 (Participant Custom
     * Field values) will add later in this feature. Until those tables exist, Postgres reports
     * "relation does not exist" ({@link BadSqlGrammarException}); that is treated defensively as
     * "no values recorded yet" so this story's create/update/delete flows work correctly today.
     * Once those tables exist, the very same query starts returning real counts and these guards
     * activate with no further code change required — writing the guard against the eventual
     * schema now means it "just works" the moment the referencing tables land.
     */
    private Mono<Long> valueReferenceCount(UUID customFieldDefinitionId) {
        return countReferencing("custom_field_values", "custom_field_definition_id", customFieldDefinitionId)
                .concatWith(countReferencing(
                        "custom_field_value_options", "custom_field_definition_id", customFieldDefinitionId))
                .reduce(0L, Long::sum);
    }

    private Mono<Long> optionValueReferenceCount(UUID optionId) {
        return countReferencing("custom_field_value_options", "custom_field_option_id", optionId);
    }

    private Mono<Long> countReferencing(String table, String column, UUID id) {
        return databaseClient
                .sql("SELECT count(*) FROM " + table + " WHERE " + column + " = :id")
                .bind("id", id)
                .mapValue(Long.class)
                .one()
                .onErrorReturn(BadSqlGrammarException.class, Long.valueOf(0L));
    }
}

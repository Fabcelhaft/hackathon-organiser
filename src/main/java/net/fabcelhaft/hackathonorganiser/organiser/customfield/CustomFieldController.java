package net.fabcelhaft.hackathonorganiser.organiser.customfield;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldConflictException;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinition;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldOption;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldService;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.reactive.result.view.Rendering;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Organiser-only views for the Custom Field Definition catalog and its options (T031;
 * contracts/catalog-management.md). Access to every route here is restricted to
 * {@code ROLE_ORGANISER} by {@code SecurityConfig}'s {@code /organiser/**} path rule (FR-022).
 */
@Controller
@RequestMapping("/organiser/custom-fields")
public class CustomFieldController {

    private final CustomFieldService customFieldService;

    public CustomFieldController(CustomFieldService customFieldService) {
        this.customFieldService = customFieldService;
    }

    @GetMapping
    public Mono<Rendering> list() {
        return Mono.just(Rendering.view("organiser/custom-fields/list")
                .modelAttribute("customFields", customFieldService.findAll())
                .build());
    }

    @GetMapping("/new")
    public Mono<Rendering> newForm() {
        return Mono.just(Rendering.view("organiser/custom-fields/form")
                .modelAttribute("fieldTypes", createableFieldTypes())
                .modelAttribute("sortIndex", 0)
                .build());
    }

    @PostMapping
    public Mono<Rendering> create(ServerWebExchange exchange) {
        // WebFlux's @RequestParam only ever reads URL query parameters, never a form-urlencoded
        // request body (unlike Spring MVC) — so form fields are read via ServerWebExchange.getFormData().
        return exchange.getFormData().flatMap(form -> {
            String label = form.getFirst("label");
            CustomFieldType fieldType = CustomFieldType.valueOf(form.getFirst("fieldType"));
            boolean required = isChecked(form.getFirst("required"));
            List<String> options = blankFilteredOptions(form.get("options"));
            boolean public_ = isChecked(form.getFirst("public_"));
            boolean overview = isChecked(form.getFirst("overview"));
            String rawSortIndex = form.getFirst("sort_index");

            // The sort_index parse is the head of the very chain the onErrorResume below is attached
            // to (feature 009, research.md §4): a malformed value becomes an error signal that takes
            // the same 200 re-render path as every other form conflict — never a synchronous throw
            // out of this lambda, which would bypass the resume and surface as a 500.
            return Mono.fromCallable(() -> parseSortIndex(rawSortIndex))
                    .flatMap(sortIndex -> customFieldService
                            .create(label, fieldType, required, options, public_, overview, sortIndex))
                    .<Rendering>map(definition -> Rendering.redirectTo("/organiser/custom-fields")
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(
                            CustomFieldConflictException.class,
                            ex -> Mono.just(Rendering.view("organiser/custom-fields/form")
                                    .modelAttribute("error", ex.getMessage())
                                    .modelAttribute("fieldTypes", createableFieldTypes())
                                    .modelAttribute("label", label)
                                    .modelAttribute("fieldType", fieldType)
                                    .modelAttribute("required", required)
                                    .modelAttribute("optionInputs", options)
                                    .modelAttribute("sortIndex", parseSortIndexOr(rawSortIndex, 0))
                                    .build()));
        });
    }

    @GetMapping("/{id}/edit")
    public Mono<Rendering> editForm(@PathVariable UUID id) {
        return customFieldService
                .findById(id)
                .flatMap(definition -> Mono.zip(
                                customFieldService.findOptions(id).collectList(),
                                customFieldService.hasRecordedValues(id))
                        .map(tuple -> editFormView(definition, tuple.getT1(), tuple.getT2(), null)))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping("/{id}")
    public Mono<Rendering> update(@PathVariable UUID id, ServerWebExchange exchange) {
        return customFieldService
                .findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(existing -> exchange.getFormData().flatMap(form -> {
                    String label = form.getFirst("label");
                    boolean required = isChecked(form.getFirst("required"));
                    String requestedTypeRaw = form.getFirst("fieldType");
                    CustomFieldType requestedType =
                            (requestedTypeRaw == null || requestedTypeRaw.isBlank())
                                    ? null
                                    : CustomFieldType.valueOf(requestedTypeRaw);
                    String rawSortIndex = form.getFirst("sort_index");

                    // Same chain shape as create(): the parse leads, so its failure reaches the resume.
                    return Mono.fromCallable(() -> parseSortIndex(rawSortIndex))
                            .flatMap(sortIndex -> customFieldService.update(
                                    id,
                                    label,
                                    required,
                                    requestedType,
                                    isChecked(form.getFirst("public_")),
                                    isChecked(form.getFirst("overview")),
                                    sortIndex))
                            .<Rendering>map(definition -> Rendering.redirectTo("/organiser/custom-fields")
                                    .status(HttpStatus.SEE_OTHER)
                                    .build())
                            .onErrorResume(CustomFieldConflictException.class, ex -> Mono.zip(
                                            customFieldService.findOptions(id).collectList(),
                                            customFieldService.hasRecordedValues(id))
                                    .map(tuple -> {
                                        existing.setLabel(label);
                                        existing.setRequired(required);
                                        // A malformed index re-shows the stored value; a valid one
                                        // that lost to another conflict is echoed back as typed.
                                        existing.setSortIndex(parseSortIndexOr(rawSortIndex, existing.getSortIndex()));
                                        return editFormView(existing, tuple.getT1(), tuple.getT2(), ex.getMessage());
                                    }));
                }));
    }

    @PostMapping("/{id}/delete")
    public Mono<Rendering> delete(@PathVariable UUID id) {
        return customFieldService
                .deleteDefinition(id)
                .then(Mono.just(Rendering.redirectTo("/organiser/custom-fields")
                        .status(HttpStatus.SEE_OTHER)
                        .build()))
                .onErrorResume(
                        CustomFieldConflictException.class,
                        ex -> Mono.just(Rendering.view("organiser/custom-fields/list")
                                .modelAttribute("customFields", customFieldService.findAll())
                                .modelAttribute("error", ex.getMessage())
                                .status(HttpStatus.CONFLICT)
                                .build()));
    }

    @PostMapping("/{id}/country/enable")
    public Mono<Rendering> enableCountry(@PathVariable UUID id) {
        return setCountryEnabled(id, true);
    }

    @PostMapping("/{id}/country/disable")
    public Mono<Rendering> disableCountry(@PathVariable UUID id) {
        return setCountryEnabled(id, false);
    }

    private Mono<Rendering> setCountryEnabled(UUID id, boolean enabled) {
        return customFieldService
                .findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(definition -> {
                    if (definition.getFieldType() != CustomFieldType.COUNTRY) {
                        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
                    }
                    return customFieldService.setCountryEnabled(enabled);
                })
                .map(definition -> Rendering.redirectTo(
                                "/organiser/custom-fields?flash=" + (enabled ? "Country+enabled." : "Country+disabled."))
                        .status(HttpStatus.SEE_OTHER)
                        .build());
    }

    @PostMapping("/{id}/options")
    public Mono<Rendering> addOption(@PathVariable UUID id, ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> {
            String label = form.getFirst("label");
            String rawSortIndex = form.getFirst("sort_index");
            // Parse first, inside the chain, so a malformed sort_index re-renders the edit page
            // (feature 009, FR-017) exactly like a duplicate label does.
            return Mono.fromCallable(() -> parseSortIndex(rawSortIndex))
                    .flatMap(sortIndex -> customFieldService.addOption(id, label, sortIndex))
                    .<Rendering>map(option -> Rendering.redirectTo("/organiser/custom-fields/" + id + "/edit")
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(CustomFieldConflictException.class, ex -> renderEditFormAfterOptionError(id, ex))
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
        });
    }

    /**
     * Changes one option's sort index (feature 009, FR-016; contracts/custom-field-ordering.md).
     * 404 unless the option exists and belongs to {@code id}; a malformed {@code sort_index}
     * re-renders the edit page with the error and stores nothing.
     */
    @PostMapping("/{id}/options/{optionId}")
    public Mono<Rendering> updateOptionSortIndex(
            @PathVariable UUID id, @PathVariable UUID optionId, ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> {
            String rawSortIndex = form.getFirst("sort_index");
            return Mono.fromCallable(() -> parseSortIndex(rawSortIndex))
                    .flatMap(sortIndex -> customFieldService.updateOptionSortIndex(id, optionId, sortIndex))
                    .<Rendering>map(option -> Rendering.redirectTo("/organiser/custom-fields/" + id + "/edit")
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(CustomFieldConflictException.class, ex -> renderEditFormAfterOptionError(id, ex))
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
        });
    }

    @PostMapping("/{id}/options/{optionId}/delete")
    public Mono<Rendering> deleteOption(@PathVariable UUID id, @PathVariable UUID optionId) {
        return customFieldService
                .deleteOption(optionId)
                .then(Mono.just(Rendering.redirectTo("/organiser/custom-fields/" + id + "/edit")
                        .status(HttpStatus.SEE_OTHER)
                        .build()))
                .onErrorResume(CustomFieldConflictException.class, ex -> renderEditFormAfterOptionError(id, ex));
    }

    private Mono<Rendering> renderEditFormAfterOptionError(UUID id, CustomFieldConflictException ex) {
        return customFieldService
                .findById(id)
                .flatMap(definition -> Mono.zip(
                                customFieldService.findOptions(id).collectList(),
                                customFieldService.hasRecordedValues(id))
                        .map(tuple -> editFormView(definition, tuple.getT1(), tuple.getT2(), ex.getMessage())));
    }

    private Rendering editFormView(
            CustomFieldDefinition definition,
            List<CustomFieldOption> existingOptions,
            boolean typeLocked,
            String error) {
        boolean isCountry = definition.getFieldType() == CustomFieldType.COUNTRY;
        Rendering.Builder<?> builder = Rendering.view("organiser/custom-fields/form")
                .modelAttribute("fieldTypes", createableFieldTypes())
                .modelAttribute("customFieldId", definition.getId())
                .modelAttribute("label", definition.getLabel())
                .modelAttribute("fieldType", definition.getFieldType())
                .modelAttribute("required", definition.isRequired())
                .modelAttribute("public_", definition.isPublic_())
                .modelAttribute("overview", definition.isOverview())
                .modelAttribute("sortIndex", definition.getSortIndex())
                .modelAttribute("isCountry", isCountry)
                .modelAttribute("countryEnabled", definition.isEnabled())
                .modelAttribute("existingOptions", existingOptions)
                // The COUNTRY row's type can never change (its own dedicated guard), on top of the
                // ordinary once-a-value-exists lock every other field type is still subject to.
                .modelAttribute("typeLocked", typeLocked || isCountry);
        // Per contracts/custom-fields-and-country.md, every one of these re-render paths (the
        // field_type lock on update, a duplicate option label on add, the option delete-guard) is
        // specified as a plain 200 re-render — unlike the two entity-level "delete" routes below,
        // which are explicitly called out as "409-style" and set that status themselves.
        if (error != null) {
            builder = builder.modelAttribute("error", error);
        }
        return builder.build();
    }

    /** {@code COUNTRY} is never an option here — it is seeded once, never created (research.md §1). */
    private static List<CustomFieldType> createableFieldTypes() {
        return Arrays.stream(CustomFieldType.values())
                .filter(type -> type != CustomFieldType.COUNTRY)
                .toList();
    }

    private static boolean isChecked(String value) {
        return "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
    }

    /**
     * The {@code sort_index} form field (feature 009, FR-004, FR-005; contracts/custom-field-ordering.md):
     * absent or blank means the default 0; otherwise it must be a whole number within {@code int}
     * range — {@link Integer#parseInt} rejects letters, decimals, and out-of-range values alike —
     * and anything else is a {@link CustomFieldConflictException} so the form re-renders with a
     * message and nothing is written. Deliberately NOT the silent {@code parseIntOrZero} coercion
     * {@code ContentPageController} uses: the spec requires rejection.
     */
    private static int parseSortIndex(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new CustomFieldConflictException("Sort index must be a whole number");
        }
    }

    /** {@link #parseSortIndex} for error re-renders: the parsed value, or {@code fallback} if malformed. */
    private static int parseSortIndexOr(String raw, int fallback) {
        try {
            return parseSortIndex(raw);
        } catch (CustomFieldConflictException ex) {
            return fallback;
        }
    }

    private static List<String> blankFilteredOptions(List<String> options) {
        if (options == null) {
            return List.of();
        }
        return options.stream().filter(s -> s != null && !s.isBlank()).toList();
    }
}

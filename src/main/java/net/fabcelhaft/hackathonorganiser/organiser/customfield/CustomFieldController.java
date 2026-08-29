package net.fabcelhaft.hackathonorganiser.organiser.customfield;

import java.util.List;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldConflictException;
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
                .modelAttribute("fieldTypes", CustomFieldType.values())
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

            return customFieldService
                    .create(label, fieldType, required, options)
                    .<Rendering>map(definition -> Rendering.redirectTo("/organiser/custom-fields")
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(
                            CustomFieldConflictException.class,
                            ex -> Mono.just(Rendering.view("organiser/custom-fields/form")
                                    .modelAttribute("error", ex.getMessage())
                                    .modelAttribute("fieldTypes", CustomFieldType.values())
                                    .modelAttribute("label", label)
                                    .modelAttribute("fieldType", fieldType)
                                    .modelAttribute("required", required)
                                    .modelAttribute("optionInputs", options)
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
                        .map(tuple -> editFormView(id, definition.getLabel(), definition.getFieldType(),
                                definition.isRequired(), tuple.getT1(), tuple.getT2(), null)))
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

                    return customFieldService
                            .update(id, label, required, requestedType)
                            .<Rendering>map(definition -> Rendering.redirectTo("/organiser/custom-fields")
                                    .status(HttpStatus.SEE_OTHER)
                                    .build())
                            .onErrorResume(CustomFieldConflictException.class, ex -> Mono.zip(
                                            customFieldService.findOptions(id).collectList(),
                                            customFieldService.hasRecordedValues(id))
                                    .map(tuple -> editFormView(
                                            id,
                                            label,
                                            existing.getFieldType(),
                                            required,
                                            tuple.getT1(),
                                            tuple.getT2(),
                                            ex.getMessage())));
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

    @PostMapping("/{id}/options")
    public Mono<Rendering> addOption(@PathVariable UUID id, ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> {
            String label = form.getFirst("label");
            return customFieldService
                    .addOption(id, label)
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
                        .map(tuple -> editFormView(id, definition.getLabel(), definition.getFieldType(),
                                definition.isRequired(), tuple.getT1(), tuple.getT2(), ex.getMessage())));
    }

    private Rendering editFormView(
            UUID id,
            String label,
            CustomFieldType fieldType,
            boolean required,
            List<CustomFieldOption> existingOptions,
            boolean typeLocked,
            String error) {
        Rendering.Builder<?> builder = Rendering.view("organiser/custom-fields/form")
                .modelAttribute("fieldTypes", CustomFieldType.values())
                .modelAttribute("customFieldId", id)
                .modelAttribute("label", label)
                .modelAttribute("fieldType", fieldType)
                .modelAttribute("required", required)
                .modelAttribute("existingOptions", existingOptions)
                .modelAttribute("typeLocked", typeLocked);
        // Per contracts/catalog-management.md, every one of these re-render paths (the field_type
        // lock on update, a duplicate option label on add, the option delete-guard) is specified
        // as a plain 200 re-render — unlike the two entity-level "delete" routes below, which are
        // explicitly called out as "409-style" and set that status themselves.
        if (error != null) {
            builder = builder.modelAttribute("error", error);
        }
        return builder.build();
    }

    private static boolean isChecked(String value) {
        return "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
    }

    private static List<String> blankFilteredOptions(List<String> options) {
        if (options == null) {
            return List.of();
        }
        return options.stream().filter(s -> s != null && !s.isBlank()).toList();
    }
}

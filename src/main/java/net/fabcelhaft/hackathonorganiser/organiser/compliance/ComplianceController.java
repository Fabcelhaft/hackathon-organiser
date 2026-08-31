package net.fabcelhaft.hackathonorganiser.organiser.compliance;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceConflictException;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceDiversityRequirement;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceService;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinition;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldService;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettings;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsConflictException;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.reactive.result.view.Rendering;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Organiser-only management of the Compliance Ruleset — Maximum/Minimum Group Members and Custom
 * Field diversity requirements (Story 6; contracts/compliance-settings-and-override.md). Access to
 * every route here is restricted to {@code ROLE_ORGANISER} by {@code SecurityConfig}'s
 * {@code /organiser/**} path rule (FR-019).
 */
@Controller
@RequestMapping("/organiser/compliance")
public class ComplianceController {

    private final OrganiserSettingsService organiserSettingsService;
    private final ComplianceService complianceService;
    private final CustomFieldService customFieldService;

    public ComplianceController(
            OrganiserSettingsService organiserSettingsService,
            ComplianceService complianceService,
            CustomFieldService customFieldService) {
        this.organiserSettingsService = organiserSettingsService;
        this.complianceService = complianceService;
        this.customFieldService = customFieldService;
    }

    @GetMapping
    public Mono<Rendering> form(@RequestParam(name = "flash", required = false) String flash) {
        return renderForm(flash, null);
    }

    @PostMapping
    public Mono<Rendering> updateRuleset(ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> {
            Integer maxGroupMembers;
            Integer minGroupMembers;
            try {
                maxGroupMembers = parseIntOrNull(form.getFirst("max_group_members"));
                minGroupMembers = parseIntOrNull(form.getFirst("min_group_members"));
            } catch (NumberFormatException ex) {
                return renderForm(null, "Maximum/Minimum Group Members must be whole numbers");
            }
            if (maxGroupMembers == null) {
                return renderForm(null, "Maximum Group Members is required");
            }
            return organiserSettingsService
                    .update(null, null, null, null, null, null, null, maxGroupMembers, minGroupMembers, null, null)
                    .<Rendering>map(settings ->
                            redirectWithFlash("Compliance settings updated."))
                    .onErrorResume(
                            OrganiserSettingsConflictException.class, ex -> renderForm(null, ex.getMessage()));
        });
    }

    @PostMapping("/diversity-requirements")
    public Mono<Rendering> addRequirement(ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> {
            UUID customFieldDefinitionId = parseUuidOrNull(form.getFirst("custom_field_definition_id"));
            int minimumDistinctValues = parseIntOrDefault(form.getFirst("minimum_distinct_values"), 2);
            return complianceService
                    .addRequirement(customFieldDefinitionId, minimumDistinctValues)
                    .<Rendering>map(requirement -> redirectWithFlash("Requirement added."))
                    .onErrorResume(ComplianceConflictException.class, ex -> renderForm(null, ex.getMessage()));
        });
    }

    @PostMapping("/diversity-requirements/{id}/delete")
    public Mono<Rendering> removeRequirement(@PathVariable UUID id) {
        return complianceService
                .removeRequirement(id)
                .then(Mono.just(redirectWithFlash("Requirement removed.")));
    }

    private Mono<Rendering> renderForm(String flash, String error) {
        return Mono.zip(
                        organiserSettingsService.current(),
                        complianceService.findAllRequirements().collectList(),
                        customFieldService.findAll().collectList())
                .map(tuple -> {
                    OrganiserSettings settings = tuple.getT1();
                    List<ComplianceDiversityRequirement> requirements = tuple.getT2();
                    List<CustomFieldDefinition> allFields = tuple.getT3();
                    Set<UUID> configuredFieldIds = requirements.stream()
                            .map(ComplianceDiversityRequirement::getCustomFieldDefinitionId)
                            .collect(Collectors.toSet());
                    List<CustomFieldDefinition> availableFields = allFields.stream()
                            .filter(field -> !configuredFieldIds.contains(field.getId()))
                            .toList();
                    Map<UUID, String> fieldLabelsById = allFields.stream()
                            .collect(Collectors.toMap(CustomFieldDefinition::getId, CustomFieldDefinition::getLabel));
                    Rendering.Builder<?> builder = Rendering.view("organiser/compliance/form")
                            .modelAttribute("settings", settings)
                            .modelAttribute("requirements", requirements)
                            .modelAttribute("fieldLabelsById", fieldLabelsById)
                            .modelAttribute("availableFields", availableFields);
                    if (flash != null) {
                        builder = builder.modelAttribute("flash", flash);
                    }
                    if (error != null) {
                        builder = builder.modelAttribute("error", error);
                    }
                    return builder.build();
                });
    }

    private static Rendering redirectWithFlash(String flash) {
        return Rendering.redirectTo("/organiser/compliance?flash=" + URLEncoder.encode(flash, StandardCharsets.UTF_8))
                .status(HttpStatus.SEE_OTHER)
                .build();
    }

    private static Integer parseIntOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Integer.valueOf(raw.trim());
    }

    private static int parseIntOrDefault(String raw, int defaultValue) {
        Integer parsed = parseIntOrNull(raw);
        return parsed == null ? defaultValue : parsed;
    }

    private static UUID parseUuidOrNull(String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

package net.fabcelhaft.hackathonorganiser.organiser.settings;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.fabcelhaft.hackathonorganiser.organisersettings.DirectoryAudience;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettings;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsConflictException;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.organisersettings.SkillDisplayMode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.reactive.result.view.Rendering;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Organiser-only view for the three global toggles (T019/T031; contracts/organiser-settings.md):
 * self-registration, self-revocation (this story), and topic-approval (added to this same
 * controller/template in User Story 4, T031). Access to every route here is restricted to
 * {@code ROLE_ORGANISER} by {@code SecurityConfig}'s {@code /organiser/**} path rule (FR-005).
 *
 * <p>Each toggle field is submitted as a hidden {@code "false"} value followed by a same-named
 * checkbox carrying {@code "true"} (form.html) — the standard HTML workaround for the fact that an
 * unchecked checkbox submits nothing at all. Reading {@link MultiValueMap#get} (not
 * {@code getFirst}) and checking for {@code "true"} anywhere in the list is robust regardless of
 * field submission order. A field missing from the form entirely (not even a hidden fallback) is
 * treated as "leave this toggle unchanged" — {@link OrganiserSettingsService#update}'s {@code null}
 * convention — which is what lets T031 add the third toggle to the template with no further
 * controller change.
 */
@Controller
@RequestMapping("/organiser/settings")
public class OrganiserSettingsController {

    private final OrganiserSettingsService organiserSettingsService;

    public OrganiserSettingsController(OrganiserSettingsService organiserSettingsService) {
        this.organiserSettingsService = organiserSettingsService;
    }

    @GetMapping
    public Mono<Rendering> form(@RequestParam(name = "flash", required = false) String flash) {
        return organiserSettingsService
                .current()
                .map(settings -> Rendering.view("organiser/settings/form")
                        .modelAttribute("settings", settings)
                        .modelAttribute("flash", flash)
                        .build());
    }

    @PostMapping
    public Mono<Rendering> update(ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> {
            // Deliberately not Mono.fromCallable(...) here: maxRegistrations is a legitimate null
            // ("no limit" / "leave unchanged"), and Mono.fromCallable treats a null return value
            // as an EMPTY Mono (Reactive Streams forbids a null onNext) — which would silently
            // short-circuit this whole chain to empty rather than proceeding, since a plain
            // Integer parse has no async component that needs a reactive wrapper anyway.
            Integer maxRegistrations;
            try {
                maxRegistrations = parseMaxRegistrations(form.getFirst("max_registrations"));
            } catch (NumberFormatException ex) {
                return organiserSettingsService
                        .current()
                        .map(settings -> formView(settings, "Maximum registrations must be a whole number"));
            }
            return organiserSettingsService
                    .update(
                            checkboxValue(form, "self_registration_enabled"),
                            checkboxValue(form, "self_revocation_enabled"),
                            checkboxValue(form, "topic_approval_required"),
                            maxRegistrations,
                            checkboxValue(form, "self_edit_enabled"),
                            checkboxValue(form, "skill_visibility_enabled"),
                            directoryAudienceValue(form),
                            null,
                            null,
                            checkboxValue(form, "topic_joining_enabled"),
                            skillDisplayModeValue(form),
                            checkboxValue(form, "compliance_visible_to_participants"))
                    .<Rendering>map(settings -> Rendering.redirectTo(
                                    "/organiser/settings?flash=" + encode("Settings updated."))
                            .status(HttpStatus.SEE_OTHER)
                            .build())
                    .onErrorResume(OrganiserSettingsConflictException.class, ex -> organiserSettingsService
                            .current()
                            .map(settings -> formView(settings, ex.getMessage())));
        });
    }

    private Rendering formView(OrganiserSettings settings, String error) {
        return Rendering.view("organiser/settings/form")
                .modelAttribute("settings", settings)
                .modelAttribute("error", error)
                .build();
    }

    private static Integer parseMaxRegistrations(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Integer.valueOf(raw.trim());
    }

    private static DirectoryAudience directoryAudienceValue(MultiValueMap<String, String> form) {
        String raw = form.getFirst("participants_directory_audience");
        return (raw == null || raw.isBlank()) ? null : DirectoryAudience.valueOf(raw);
    }

    private static SkillDisplayMode skillDisplayModeValue(MultiValueMap<String, String> form) {
        String raw = form.getFirst("skill_display_mode");
        return (raw == null || raw.isBlank()) ? null : SkillDisplayMode.valueOf(raw);
    }

    private static Boolean checkboxValue(MultiValueMap<String, String> form, String name) {
        List<String> values = form.get(name);
        return values == null ? null : values.contains("true");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

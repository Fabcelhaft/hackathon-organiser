package net.fabcelhaft.hackathonorganiser.participants;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinition;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldService;
import net.fabcelhaft.hackathonorganiser.customfield.IsoCountryCatalog;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettings;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantConflictException;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantService;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantService.CustomFieldValueView;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
import net.fabcelhaft.hackathonorganiser.participant.ProfileFormSubmission;
import net.fabcelhaft.hackathonorganiser.participant.ProfileFormSubmission.FieldAnswer;
import net.fabcelhaft.hackathonorganiser.participant.ProfileFormSubmission.FreeText;
import net.fabcelhaft.hackathonorganiser.participant.ProfileFormSubmission.Options;
import net.fabcelhaft.hackathonorganiser.participant.RegistrationCapacityReachedException;
import net.fabcelhaft.hackathonorganiser.security.HackathonOidcUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.reactive.result.view.Rendering;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The registration/reactivation form (T018; contracts/registration-and-self-edit.md): {@code GET}/
 * {@code POST /register}. Supersedes 003's immediate {@code POST /register} button — the homepage
 * now links here instead (FR-001). Sits outside {@code /organiser/**}, gated by plain
 * authentication like {@code home}/{@code info}/{@code topics} (research.md, plan.md Structure
 * Decision): every registered/unregistered authenticated user may reach it.
 */
@Controller
public class RegistrationController {

    private final ParticipantService participantService;
    private final OrganiserSettingsService organiserSettingsService;
    private final CustomFieldService customFieldService;

    public RegistrationController(
            ParticipantService participantService,
            OrganiserSettingsService organiserSettingsService,
            CustomFieldService customFieldService) {
        this.participantService = participantService;
        this.organiserSettingsService = organiserSettingsService;
        this.customFieldService = customFieldService;
    }

    @GetMapping("/register")
    public Mono<Rendering> registerForm(@AuthenticationPrincipal HackathonOidcUser oidcUser) {
        UUID userId = oidcUser.getUser().getId();
        return Mono.zip(
                        participantService.findByUserId(userId).map(Optional::of).defaultIfEmpty(Optional.empty()),
                        organiserSettingsService.current())
                .flatMap(tuple -> {
                    Optional<Participant> participantOpt = tuple.getT1();
                    OrganiserSettings settings = tuple.getT2();
                    if (!settings.isSelfRegistrationEnabled()) {
                        return Mono.just(
                                redirectHomeWithFlash(ParticipantService.SELF_REGISTRATION_DISABLED_MESSAGE));
                    }
                    if (isNotParticipated(participantOpt)) {
                        return Mono.just(redirectHomeWithFlash(ParticipantService.NOT_PARTICIPATED_MESSAGE));
                    }
                    return participantService
                            .isAtRegistrationCapacity()
                            .flatMap(atCapacity -> atCapacity
                                    ? Mono.just(capacityMessageView())
                                    : renderForm(userId, null));
                });
    }

    @PostMapping("/register")
    public Mono<Rendering> submitRegister(@AuthenticationPrincipal HackathonOidcUser oidcUser, ServerWebExchange exchange) {
        UUID userId = oidcUser.getUser().getId();
        return exchange.getFormData()
                .flatMap(form -> customFieldService.registrationFields().collectList().flatMap(fields -> {
                    ProfileFormSubmission submission = parseSubmission(form, fields);
                    return participantService
                            .submitRegistration(userId, submission)
                            .<Rendering>map(participant -> redirectHomeWithFlash("Registration successful."))
                            .onErrorResume(RegistrationCapacityReachedException.class, ex -> Mono.just(capacityMessageView()))
                            .onErrorResume(ParticipantConflictException.class, ex -> {
                                if (isLockoutOrDisabledMessage(ex.getMessage())) {
                                    return Mono.just(redirectHomeWithFlash(ex.getMessage()));
                                }
                                return renderFormWithSubmittedValues(fields, submission, ex.getMessage());
                            });
                }));
    }

    // --- Rendering ---------------------------------------------------------------------------------

    private Mono<Rendering> renderForm(UUID userId, String error) {
        return Mono.zip(
                        participantService.registrationFieldViewsForUser(userId),
                        participantService.allSkills().collectList(),
                        participantService.currentSkillIdsForUser(userId))
                .map(tuple -> formView(tuple.getT1(), tuple.getT2(), tuple.getT3(), error));
    }

    private Mono<Rendering> renderFormWithSubmittedValues(
            List<CustomFieldDefinition> fields, ProfileFormSubmission submission, String error) {
        return Mono.zip(viewsFromSubmission(fields, submission), participantService.allSkills().collectList())
                .map(tuple -> formView(tuple.getT1(), tuple.getT2(), submission.skillIds(), error));
    }

    private Rendering formView(
            List<CustomFieldValueView> fields,
            List<net.fabcelhaft.hackathonorganiser.skill.Skill> skills,
            List<UUID> selectedSkillIds,
            String error) {
        Rendering.Builder<?> builder = Rendering.view("participants/register")
                .modelAttribute("fields", fields)
                .modelAttribute("skills", skills)
                .modelAttribute("selectedSkillIds", selectedSkillIds)
                .modelAttribute("countries", IsoCountryCatalog.all())
                .modelAttribute("atCapacity", false);
        if (error != null) {
            builder = builder.modelAttribute("error", error);
        }
        return builder.build();
    }

    private Rendering capacityMessageView() {
        return Rendering.view("participants/register")
                .modelAttribute("atCapacity", true)
                .build();
    }

    // --- Form parsing --------------------------------------------------------------------------------

    private ProfileFormSubmission parseSubmission(
            MultiValueMap<String, String> form, List<CustomFieldDefinition> fields) {
        Map<UUID, FieldAnswer> answers = new LinkedHashMap<>();
        for (CustomFieldDefinition definition : fields) {
            List<String> values = form.get("field_" + definition.getId());
            FieldAnswer answer =
                    switch (definition.getFieldType()) {
                        case FREE_TEXT, COUNTRY -> new FreeText(firstNonBlank(values));
                        case SINGLE_SELECT, MULTI_SELECT -> new Options(toUuidSet(values));
                    };
            answers.put(definition.getId(), answer);
        }
        return new ProfileFormSubmission(answers, toUuidList(form.get("skillIds")));
    }

    private Mono<List<CustomFieldValueView>> viewsFromSubmission(
            List<CustomFieldDefinition> fields, ProfileFormSubmission submission) {
        return Flux.fromIterable(fields)
                .concatMap(definition -> customFieldService
                        .findOptions(definition.getId())
                        .collectList()
                        .map(options -> {
                            FieldAnswer answer = submission.answers().get(definition.getId());
                            String freeText = (answer instanceof FreeText freeTextAnswer) ? freeTextAnswer.value() : "";
                            List<UUID> selectedIds =
                                    (answer instanceof Options optionsAnswer) ? List.copyOf(optionsAnswer.optionIds()) : List.of();
                            return new CustomFieldValueView(
                                    definition, options, freeText == null ? "" : freeText, selectedIds);
                        }))
                .collectList();
    }

    // --- Helpers ------------------------------------------------------------------------------------

    private static boolean isNotParticipated(Optional<Participant> participantOpt) {
        return participantOpt.map(p -> p.getStatus() == ParticipantStatus.NOT_PARTICIPATED).orElse(false);
    }

    private static boolean isLockoutOrDisabledMessage(String message) {
        return ParticipantService.NOT_PARTICIPATED_MESSAGE.equals(message)
                || ParticipantService.SELF_REGISTRATION_DISABLED_MESSAGE.equals(message);
    }

    private static String firstNonBlank(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.get(0);
        return (value == null || value.isBlank()) ? null : value;
    }

    private static Set<UUID> toUuidSet(List<String> raw) {
        if (raw == null) {
            return Set.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (String value : raw) {
            if (value != null && !value.isBlank()) {
                ids.add(UUID.fromString(value));
            }
        }
        return ids;
    }

    private static List<UUID> toUuidList(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().filter(s -> s != null && !s.isBlank()).map(UUID::fromString).toList();
    }

    static Rendering redirectHomeWithFlash(String flash) {
        return Rendering.redirectTo("/?flash=" + URLEncoder.encode(flash, StandardCharsets.UTF_8))
                .status(HttpStatus.SEE_OTHER)
                .build();
    }
}

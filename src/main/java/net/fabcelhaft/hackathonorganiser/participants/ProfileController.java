package net.fabcelhaft.hackathonorganiser.participants;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.audit.AuditActor;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldDefinition;
import net.fabcelhaft.hackathonorganiser.customfield.CustomFieldService;
import net.fabcelhaft.hackathonorganiser.customfield.IsoCountryCatalog;
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
 * A Participant's own profile: read-only view and self-edit form (T037;
 * contracts/registration-and-self-edit.md). {@code GET /profile} always shows the caller's current
 * values regardless of {@code selfEditEnabled} or {@code NOT_PARTICIPATED} status (FR-023) — a
 * dead-end status still permits viewing one's own stored values.
 */
@Controller
public class ProfileController {

    private final ParticipantService participantService;
    private final OrganiserSettingsService organiserSettingsService;
    private final CustomFieldService customFieldService;
    private final ParticipantsDirectoryController directoryController;

    public ProfileController(
            ParticipantService participantService,
            OrganiserSettingsService organiserSettingsService,
            CustomFieldService customFieldService,
            ParticipantsDirectoryController directoryController) {
        this.participantService = participantService;
        this.organiserSettingsService = organiserSettingsService;
        this.customFieldService = customFieldService;
        this.directoryController = directoryController;
    }

    /**
     * A thin delegate to {@link ParticipantsDirectoryController#renderDetail}'s self-mode path
     * (T047) — the caller's own id as the target, so there is exactly one detail-rendering code
     * path shared with {@code GET /participants/{id}} in self mode, never a second copy of the
     * visibility logic.
     */
    @GetMapping("/profile")
    public Mono<Rendering> profile(@AuthenticationPrincipal HackathonOidcUser oidcUser) {
        UUID userId = oidcUser.getUser().getId();
        return participantService
                .findByUserId(userId)
                .flatMap(participant ->
                        directoryController.renderDetail(participant.getId(), userId, oidcUser.getUser().isOrganiser()))
                .switchIfEmpty(Mono.just(Rendering.redirectTo("/register")
                        .status(HttpStatus.SEE_OTHER)
                        .build()));
    }

    @GetMapping("/profile/edit")
    public Mono<Rendering> editForm(@AuthenticationPrincipal HackathonOidcUser oidcUser) {
        UUID userId = oidcUser.getUser().getId();
        return Mono.zip(
                        participantService.findByUserId(userId).map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty()),
                        organiserSettingsService.current())
                .flatMap(tuple -> {
                    java.util.Optional<Participant> participantOpt = tuple.getT1();
                    boolean editable = tuple.getT2().isSelfEditEnabled()
                            && participantOpt.isPresent()
                            && participantOpt.get().getStatus() != ParticipantStatus.NOT_PARTICIPATED;
                    if (!editable) {
                        return Mono.just(redirectToProfile(null));
                    }
                    return renderEditForm(participantOpt.get().getId(), null);
                });
    }

    @PostMapping("/profile/edit")
    public Mono<Rendering> submitEdit(@AuthenticationPrincipal HackathonOidcUser oidcUser, ServerWebExchange exchange) {
        UUID userId = oidcUser.getUser().getId();
        return participantService
                .findByUserId(userId)
                .flatMap(participant -> exchange.getFormData()
                        .flatMap(form -> customFieldService.registrationFields().collectList().flatMap(fields -> {
                            ProfileFormSubmission submission = parseSubmission(form, fields);
                            return participantService
                                    .submitSelfEdit(participant.getId(), submission, new AuditActor(userId, false))
                                    .<Rendering>map(p -> redirectToProfile("Profile updated."))
                                    .onErrorResume(ParticipantConflictException.class, ex -> {
                                        if (isLockoutOrDisabledMessage(ex.getMessage())) {
                                            return Mono.just(redirectToProfile(ex.getMessage()));
                                        }
                                        return renderFormWithSubmittedValues(fields, submission, ex.getMessage());
                                    });
                        })))
                .switchIfEmpty(Mono.just(redirectToProfile(null)));
    }

    // --- Rendering ---------------------------------------------------------------------------------

    private Mono<Rendering> renderEditForm(UUID participantId, String error) {
        return Mono.zip(
                        participantService.registrationFieldViewsForParticipant(participantId),
                        participantService.allSkills().collectList(),
                        participantService.currentSkillIdsForParticipant(participantId),
                        organiserSettingsService.current())
                .map(tuple -> formView(
                        tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4().isSkillVisibilityEnabled(), error));
    }

    private Mono<Rendering> renderFormWithSubmittedValues(
            List<CustomFieldDefinition> fields, ProfileFormSubmission submission, String error) {
        return Mono.zip(
                        viewsFromSubmission(fields, submission),
                        participantService.allSkills().collectList(),
                        organiserSettingsService.current())
                .map(tuple -> formView(
                        tuple.getT1(),
                        tuple.getT2(),
                        submission.skillIds(),
                        tuple.getT3().isSkillVisibilityEnabled(),
                        error));
    }

    private Rendering formView(
            List<CustomFieldValueView> fields,
            List<net.fabcelhaft.hackathonorganiser.skill.Skill> skills,
            List<UUID> selectedSkillIds,
            boolean skillsVisibleToOthers,
            String error) {
        Rendering.Builder<?> builder = Rendering.view("participants/edit")
                .modelAttribute("fields", fields)
                .modelAttribute("skills", skills)
                .modelAttribute("selectedSkillIds", selectedSkillIds)
                .modelAttribute("countries", IsoCountryCatalog.all())
                .modelAttribute("showVisibility", true)
                .modelAttribute("skillsVisibleToOthers", skillsVisibleToOthers);
        if (error != null) {
            builder = builder.modelAttribute("error", error);
        }
        return builder.build();
    }

    private static Rendering redirectToProfile(String flash) {
        String location = flash == null ? "/profile" : "/profile?flash=" + URLEncoder.encode(flash, StandardCharsets.UTF_8);
        return Rendering.redirectTo(location).status(HttpStatus.SEE_OTHER).build();
    }

    // --- Form parsing (shared shape with RegistrationController) --------------------------------

    private ProfileFormSubmission parseSubmission(
            MultiValueMap<String, String> form, List<CustomFieldDefinition> fields) {
        Map<UUID, FieldAnswer> answers = new java.util.LinkedHashMap<>();
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

    private static boolean isLockoutOrDisabledMessage(String message) {
        return ParticipantService.NOT_PARTICIPATED_MESSAGE.equals(message)
                || ParticipantService.SELF_EDIT_DISABLED_MESSAGE.equals(message);
    }

    private static String firstNonBlank(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.get(0);
        return (value == null || value.isBlank()) ? null : value;
    }

    private static java.util.Set<UUID> toUuidSet(List<String> raw) {
        if (raw == null) {
            return java.util.Set.of();
        }
        java.util.Set<UUID> ids = new java.util.LinkedHashSet<>();
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
}

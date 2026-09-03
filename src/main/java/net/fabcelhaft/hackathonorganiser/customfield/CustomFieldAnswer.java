package net.fabcelhaft.hackathonorganiser.customfield;

import java.util.List;
import java.util.UUID;

/**
 * One {@link CustomFieldDefinition} paired with a specific Participant's stored answer to it
 * (data-model.md "Custom Field Value"; spec.md FR-013, FR-014, FR-010d). {@code options} is every
 * option currently defined for a {@code MULTI_SELECT}/{@code SINGLE_SELECT} definition (empty for
 * every other field type); {@code freeTextValue}/{@code selectedOptionIds} are blank/empty when
 * the Participant has not answered that field.
 *
 * <p>Relocated here from {@code participant.ParticipantService.CustomFieldValueView} (research.md
 * §10 of feature 007) so both {@code ParticipantService} and {@code event.EventPayloadFactory} can
 * depend on the same shape without either depending on the other's package — {@code participant}
 * already depends on {@code event}, so the reverse edge would be a bean-construction cycle.
 */
public record CustomFieldAnswer(
        CustomFieldDefinition definition,
        List<CustomFieldOption> options,
        String freeTextValue,
        List<UUID> selectedOptionIds) {}

package net.fabcelhaft.hackathonorganiser.participant;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The parsed shape of one registration/self-edit form {@code POST} (data-model.md
 * "ProfileFormSubmission"): one {@link FieldAnswer} per submitted {@code CustomFieldDefinition} id
 * — a free-text string for {@code FREE_TEXT}/{@code COUNTRY} fields (a Country's value is its
 * chosen ISO alpha-2 code, reusing the same free-text carrier the storage layer reuses,
 * research.md §1), or a set of chosen option ids for {@code SINGLE_SELECT}/{@code MULTI_SELECT}
 * fields — plus the submitted Skill selection. Purely a service-layer/controller-layer DTO, never
 * persisted.
 */
public record ProfileFormSubmission(Map<UUID, FieldAnswer> answers, List<UUID> skillIds) {

    public sealed interface FieldAnswer permits FreeText, Options {}

    public record FreeText(String value) implements FieldAnswer {}

    public record Options(Set<UUID> optionIds) implements FieldAnswer {}
}

# Contract: Topic Proposal & Skills (Story 1 — FR-001, FR-002, FR-003)

`topics` package (self-service, outside `/organiser/**`), extending 003's existing propose/edit routes with a
Skill picker. No new routes — `GET /topics/new`, `POST /topics`, `GET /topics/{id}/edit`, `POST /topics/{id}`
all keep their existing paths and status codes; only the form fields and the service parameters change.

## GET /topics/new — unchanged path, extended form

**Modified** (`TopicSelfServiceController`). Renders `topics/form.html`, now including the full Skill catalog
(`TopicService.allSkills()`, already existing) as a multi-select — no Skill pre-selected, mirroring
`organiser/topics/form.html`'s already-proven markup. Skill selection is optional (FR-001, Acceptance
Scenario 3): the form can be submitted with none checked.

## POST /topics — unchanged path, extended request/behavior

**Modified**. Request gains a `skillIds` (repeated) form field alongside the existing `name`/`description`.

- **303 → `/?flash=...`** — `name`/`description` present: `TopicService.propose(userId, name, description,
  skillIds)` (data-model.md "Topic") creates the Topic with the submitted Skills attached (FR-001) and the
  caller recorded as author, exactly as before this feature plus the Skill association write.
- **200, form re-rendered with an error** — blank `name`/`description` (unchanged validation from 003); a
  `skillIds` entry that doesn't match any existing Skill is rejected the same way `organiser/topics/form.html`'s
  equivalent submission already is (`TopicConflictException`, "One or more selected skills do not exist"),
  with the submitted Skill checkboxes preserved so the user doesn't lose their selections.

## GET /topics/{id}/edit — unchanged path, extended pre-fill

**Modified**. Pre-fills the Skill multi-select from `TopicService.findDetail(id).skillIds()` (already exists),
in addition to the existing `name`/`description` pre-fill.

## POST /topics/{id} — unchanged path, extended request/behavior

**Modified**. Same `skillIds` field addition as `POST /topics`; `TopicService.updateAsAuthor(id, userId, name,
description, skillIds)` replaces the Topic's Skill association set (FR-002, Acceptance Scenario 2) using the
same `replaceTopicSkills` write `create`/`update` already use — the updated list is reflected everywhere the
Topic is shown (Home Page, Topic Overview) on their very next render, no caching involved.

## Not changed by this contract

- Authorization: the existing `findVisibleTo` + authorship check (`failIfNotAuthor`) is unchanged — Skills are
  editable only by the same author/Organiser-or-nobody rule already governing `name`/`description`.
- `organiser/topics/form.html` and `TopicService.create`/`update` (the Organiser-facing routes) are unchanged —
  they already had a Skill picker before this feature.

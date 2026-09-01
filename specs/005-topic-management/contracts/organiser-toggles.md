# Contract: Topic-Joining-Enabled & Skill Display Mode Toggles (Stories 4, 8 — FR-017, FR-018, FR-020a–d)

Both settings live on the existing singleton `organiser_settings` row (data-model.md), edited via the existing
`GET`/`POST /organiser/settings` route pair (`organiser.settings.OrganiserSettingsController`,
`organiser/settings/form.html`) alongside 003/004's existing toggles — no new route.

## GET /organiser/settings — extended form, same route

**Modified**. The existing settings form gains two more controls:

- **Topic joining enabled** — a checkbox, defaulting to the seeded `true` (FR-020d) on a fresh instance.
- **Skill Display Mode** — a two-option radio group (`STILL_NEEDED_ONLY` / `ALL_ASSOCIATED`), defaulting to
  `STILL_NEEDED_ONLY` (FR-017) on a fresh instance.

Both, like every existing toggle on this form, show their currently-saved value on every load — never a
client-cached one.

## POST /organiser/settings — extended request/behavior, same route

**Modified**. `OrganiserSettingsService.update(...)` gains two more `null`-means-"leave unchanged" parameters
for the fields above, validated the same way every other field on this form already is (no invalid state is
possible for a checkbox/two-option radio, so neither field can itself cause a rejected save).

- **303 → `/organiser/settings?flash=Settings+updated.`** — always, for these two fields specifically (no
  validation to fail).
- Effective immediately, no deployment required, on the very next relevant read (FR-018, FR-020d):
  - Disabling Topic joining removes the "Join" action from the Home Page for every Participant on their very
    next view (SC-009) and causes every in-flight `POST /topics/{id}/join` to be rejected server-side
    regardless of what the page displayed at load (FR-020c, `join-action.md`).
  - Re-enabling it restores the action for every eligible Participant immediately (SC-009).
  - Changing the Skill Display Mode changes the Skills column on both the Home Page and the Topic Overview for
    every user on their next view (FR-018, SC-004-equivalent freshness for this setting).

## Not a new route: Custom Field Definition delete-guard extension (Edge Cases)

`organiser/custom-fields/list.html`'s existing delete action (`CustomFieldService.deleteDefinition`) is
unchanged at the route level; its existing reference-blocking response ("Cannot delete this custom field:
still referenced by...") gains a compliance-requirement-aware count (research.md §8, `compliance-settings-
and-override.md`'s Custom Field reference note) — an Organiser attempting to delete a Custom Field Definition
currently used by a diversity requirement now sees that blocked exactly like a Field still holding Participant
values is blocked today, with a message identifying the compliance-rule reference specifically.

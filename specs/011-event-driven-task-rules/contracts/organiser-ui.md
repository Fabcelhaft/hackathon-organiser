# Contract: Organiser UI Surface

**Feature**: `011-event-driven-task-rules` | **Spec**: [spec.md](../spec.md)

Every route is under `/organiser/**` and therefore already restricted to `ROLE_ORGANISER` by
`SecurityConfig`'s existing path rule — no security configuration changes (FR-030). CSRF is disabled
project-wide, so forms carry no token field, matching every existing organiser form.

All handlers return `Mono<Rendering>` and follow the POST → redirect → GET pattern used throughout
the organiser area (Constitution Principle VI: no `void` controller return types).

---

## New routes — `TaskController`, `/organiser/tasks`

| Method | Path | Purpose | Requirement |
|---|---|---|---|
| `GET` | `/organiser/tasks` | The Task list. `?show=done` includes done Tasks; absent means undone only | FR-020, FR-022, FR-023 |
| `POST` | `/organiser/tasks/{id}/assign` | Save that row's assignee | FR-024 |
| `POST` | `/organiser/tasks/{id}/done` | Mark done | FR-025, FR-027 |
| `POST` | `/organiser/tasks/{id}/reopen` | Reopen a done Task | FR-026, FR-027 |

**Filter round-trip (FR-023)**: each of the three POST forms carries the current `show` value as a
hidden field and redirects back to `/organiser/tasks?show=…`, so an action taken from the
done-inclusive view returns to it rather than dropping the Organiser back to the default.

**`assign` form field**: `assignee_user_id`, whose value is a User id or the empty string for
unassigned (FR-021). An id that is not a current Organiser is treated as unassigned rather than
rejected.

**Idempotence (FR-027)**: `done` on an already-done Task and `reopen` on an already-undone one both
redirect normally. They are conditional updates affecting zero rows — not an error, not a flash
message.

---

## Changed routes — `EventDestinationController`, unchanged paths

No route is added, removed, or renamed. The existing create and update handlers read two additional
form fields when `type=TASK`:

| Field | Meaning |
|---|---|
| `task_title_pattern` | The pattern, unresolved |
| `task_default_assignee_user_id` | A User id, or empty for none |

`GET /organiser/event-destinations/new` and `/{id}/edit` additionally place the Organiser list in the
model, for the default-assignee picker.

`POST /organiser/event-destinations/{id}/delete` gains no new path, but its button now carries a
`data-confirm` message naming the number of undone Tasks that will be removed (FR-002a).

---

## Views

### `organiser/tasks/list.html` — new

A four-column table (FR-021), one row per Task:

| Column | Contents |
|---|---|
| Title | the resolved title, with created time — and completion time on a done row — as secondary text beneath it (FR-021a) |
| Assignee | a `<select>` of current Organisers plus an "Unassigned" option |
| — | Save button |
| — | Done button, or Reopen on a done row |

Above the table: the filter control. Below, when the done-inclusive view was cut off, the notice
required by FR-028a. When nothing matches, the empty state required by FR-029.

**No fifth column may be added.** FR-021a places the timestamps inside the title cell precisely so
the table stays at four columns.

**Accessibility (FR-031)**: every row's controls repeat the same three labels, so each Save, Done,
Reopen, and assignee `<select>` needs an accessible name identifying *which* Task it acts on — an
`aria-label` naming the Task's title. Without it, a screen reader hears "Save, Save, Save".

### `organiser/event-destinations/list.html` — changed

- The Connection column shows the title pattern for a `TASK` row, where it shows a broker or URL for
  the other two types.
- An **inert marker** on any row that is enabled and has no Event Types selected — for all three
  handler types, not only Task Rules (FR-032a). Computed from the `DestinationRow` data already
  loaded; no new query.
- The Delete button carries the `data-confirm` message described above, reusing the native
  `confirm()` wiring already present in `organiser/content-pages/list.html`.

### `organiser/event-destinations/form.html` — changed

- A third `<option value="TASK">Task</option>` in the Type dropdown.
- A `taskFields` group — title pattern, with the syntax and a worked example as help text (FR-008);
  default assignee picker — shown and hidden by the existing `toggleTypeFields()` function, extended
  from a two-way to a three-way switch.
- The Credential field is hidden when the type is `TASK` (FR-033 — inputs with no meaning for a Task
  Rule are not shown).

### `organiser/fragments/layout.html` — changed

Exactly one new nav entry, **Tasks**, linking to `/organiser/tasks` (FR-020). No second entry for
Rule configuration — it lives on the existing Event Destinations screens.

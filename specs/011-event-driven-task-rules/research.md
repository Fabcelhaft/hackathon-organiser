# Phase 0 Research: Event-Driven Task Rules

**Feature**: `011-event-driven-task-rules` | **Date**: 2026-09-19 | **Spec**: [spec.md](./spec.md)

Every decision below is grounded in a precedent that already exists in this codebase, except §3 and
§4, which are genuinely new mechanisms and are marked as such.

---

## §1 — Where a Task Rule is stored

**Decision**: Add `TASK` to the existing `EventDestinationType` enum and store Task Rules as rows in
the existing `event_destinations` table, with two new nullable columns: `task_title_pattern` and
`task_default_assignee_user_id`. Widen the existing
`event_destinations_type_fields_check` CHECK constraint with a third branch.

**Rationale**: FR-032, FR-033, and FR-034 require Task Rules to appear in the same list, be edited
through the same form, and behave identically for name, enabled state, and Event Type selection.
FR-003 additionally requires the name to be unique *across all handler types*. One table delivers
all of this for free:

- `event_destinations_name_key` (the existing unique index) already enforces FR-003's cross-type
  uniqueness. Two tables would make it a two-table check with a race between them.
- `EventDestinationService` already implements create, update, enable, disable, delete, the
  `updatedAt` stale-write guard, and Event Type subscription replacement. A separate entity would
  duplicate every one of those.
- `EventPublisher.dispatch` already branches on `destination.getType()`. A third branch is one line.

**Alternatives considered**:

- *A separate `task_rules` table with its own service and repository.* Rejected — duplicates the
  whole of `EventDestinationService`, and turns a single unique index into a cross-table check.
- *Reuse `http_url` to hold the title pattern.* Rejected — overloads a column with an unrelated
  meaning, defeats the CHECK constraint's purpose, and would confuse every existing query.

---

## §2 — What the wildcards resolve against

**Decision**: Resolve wildcard paths against the **already-serialized JSON envelope**, parsed back
into a Jackson `JsonNode`, rather than against the in-memory `Map<String, Object>` payload.

**Rationale**: This is the decision that makes FR-011a correct by construction rather than by
re-implementation. `EventPublisher.serialize(DomainEvent)` already produces the exact envelope that
Kafka and HTTP POST destinations receive — `{"eventType": "...", ...payload}` — with
`JavaTimeModule` registered and `WRITE_DATES_AS_TIMESTAMPS` disabled, so an `Instant` is already
ISO-8601 text and a `UUID` is already its canonical string.

FR-011a requires a value to be written "exactly as it appears in the Event's data". The serialized
JSON *is* the Event's data as published. If the resolver walked the raw `Map` instead, it would meet
a live `Instant` object and a live `UUID` object and would have to re-implement Jackson's rendering
of each — which could silently drift from what a webhook subscriber sees, breaking the spec's stated
premise that wildcards address "the same content the existing Kafka and HTTP POST handlers already
send".

**Cost**: one `readTree` parse per TASK-type destination per Event. It runs only for TASK
destinations, on the already-detached publishing pipeline (§6), so it is off every user-facing path.

**Alternatives considered**:

- *Navigate the `Map<String, Object>` directly.* Rejected — duplicates Jackson's value rendering and
  risks drifting from the published form.
- *Pass both the Map and the JSON string down to the sender.* Rejected — widens the shared
  `dispatch` signature to buy nothing the parse does not already give.

---

## §3 — Path navigation, and the `NullNode` trap (new mechanism)

**Decision**: Walk the `JsonNode` segment by segment. At each step, if the current node is an array
**and** the segment is all digits, index into it; otherwise treat the segment as a field name. Emit
the value only when `node.isValueNode() && !node.isNull()`, using `asText()`. Everything else — a
missing field, an out-of-range index, an object, an array, or a JSON null — yields the empty string.

**Rationale, and the trap worth pinning down**: Jackson's `JsonNode.isValueNode()` returns **`true`**
for `NullNode`, and `NullNode.asText()` returns the four-character string `"null"`. The obvious
implementation — `if (node.isValueNode()) return node.asText();` — therefore writes the literal word
`null` into a Task title, which FR-012 and the "Wildcard resolves to an empty value" edge case both
explicitly forbid. The `&& !node.isNull()` guard is not defensive noise; it is the requirement.

Deciding array-vs-field by inspecting the *current node* rather than the segment's shape also
removes an ambiguity the spec does not address: an object with a numeric-looking key (none exists in
today's payloads, but nothing prevents one) resolves as a field, while a genuine list resolves by
position, and neither case needs new syntax.

**FR-012's three blank cases collapse to one code path**, which is precisely what the requirement
asks for ("These three cases MUST be indistinguishable in the resulting title").

**Alternatives considered**:

- *Bracket indexing (`customFields[0]`).* Rejected at clarification — a second notation with its own
  parsing and error cases.
- *A JSONPath library.* Rejected — a new dependency for a fraction of its surface, and the
  constitution's Principle I keeps the dependency surface minimal.

---

## §4 — Pattern scanning and validation (new mechanism)

**Decision**: One left-to-right scanner, shared by validation (FR-007) and rendering (FR-011). It
walks the pattern looking for `{{`, then for the next `}}`, and treats everything else as literal.

**Rationale**: A regular expression such as `\{\{([^{}]*)\}\}` finds well-formed wildcards but is
structurally unable to report an **unclosed** one — it simply does not match, and the malformed text
passes through as literal, which is exactly the silent failure FR-007 exists to prevent. Detecting
"a `{{` with no closing `}}`" requires an explicit scan.

Sharing one scanner between validation and rendering guarantees the two can never disagree about
what counts as a wildcard — a pattern that saved cleanly can never surprise the renderer later.

**Validation rejects** (FR-007): an empty pattern; a `{{` with no matching `}}`; an empty path
(`{{}}`); an empty segment from a leading, trailing, or doubled dot (`{{.a}}`, `{{a.}}`, `{{a..b}}`).
A single `{` or `}` is literal text (FR-009) and never rejected.

---

## §5 — Order of operations when building a title

**Decision**: resolve wildcards → trim → if the result is blank, substitute the fallback → then
truncate to 500 characters ending in an ellipsis.

**Rationale**: FR-014 (blank → fallback) and FR-015 (500-character cap) interact, and the order
matters. Truncating before the blank check is harmless but pointless; applying the fallback before
truncation guarantees the fallback — which is short by construction, naming the Rule and Event
Type — is never itself clipped. The ellipsis counts inside the 500 characters, matching FR-015's
wording ("shortened to 500 characters, ending with an ellipsis").

---

## §6 — Task creation stays off the triggering request's path

**Decision**: Add `TaskDestinationSender` with the same shape as `KafkaDestinationSender` and
`HttpDestinationSender` — `Mono<Void> send(EventDestination, String jsonBody)` that never signals an
error — and add a third branch to `EventPublisher.dispatch`.

**Rationale**: `EventPublisher` already `.subscribe()`s each delivery `Mono` as a detached pipeline
the caller never awaits, which is the existing mechanism behind feature 007's FR-020a-1. Slotting
Task creation in as a third sender inherits that guarantee unchanged, so FR-018 and SC-003 hold by
construction rather than by new code. A failure to create a Task is logged and swallowed exactly as a
failed HTTP delivery already is.

This also means **no existing triggering service changes at all** — `ParticipantService`,
`TopicService`, `GroupService`, `UserService` and the rest already call `EventPublisher`, and gain
Task creation for free.

---

## §7 — Deleting a Rule, and keeping the record of a done Task

**Decision**: `tasks.event_destination_id` is **nullable** with `ON DELETE SET NULL`, and the row
*also* carries a `rule_name` text snapshot taken at creation. `EventDestinationService.delete`
explicitly removes undone Tasks first; the database then nulls the reference on the surviving done
ones.

**Rationale**: FR-002a splits deletion — undone Tasks go, done ones stay as a record. A plain
`ON DELETE CASCADE` cannot express that split, and leaving the FK non-null would make the destination
row undeletable while done Tasks referenced it. Deleting the undone rows explicitly and letting the
FK null itself for the rest handles both halves in one transaction-shaped sequence.

The `rule_name` snapshot exists because FR-017 requires a Task to record which Rule produced it, and
`ON DELETE SET NULL` would otherwise erase exactly that for the done Tasks the requirement is trying
to preserve. This mirrors the precedent `ParticipantService` already sets, writing an audit
`subject_label` snapshot before a delete so the record outlives the row — the same precedent the
spec's personal-data clarification leaned on.

**Confirmation count**: the list controller counts undone Tasks per Rule and renders the number into
a `data-confirm` attribute, reusing feature 008's native `confirm()` wiring in
`organiser/content-pages/list.html` verbatim. No modal component, no new dependency.

---

## §8 — The inert-handler marker

**Decision**: Compute it in the existing list view from data already loaded — a row is inert when
`destination.isEnabled()` and its `eventTypes()` list is empty.

**Rationale**: `EventDestinationController.list` already builds a `DestinationRow(destination,
eventTypes)` per row, so FR-032a costs zero additional queries and no schema change. It applies to
all three handler types automatically, which is what the requirement asks for.

---

## §9 — Assignee options and a stale assignee

**Decision**: Add `Flux<User> findByOrganiserTrueOrderByDisplayNameAsc()` to `UserRepository` (a
derived query, no SQL). Load the list once per page render and share it across every row. A Task
whose stored assignee is no longer an Organiser renders as unassigned.

**Rationale**: FR-021 needs the same option list on every row; loading it per row would be N+1 for no
benefit. The stale-assignee behaviour is required by the "assigned User loses Organiser rights" edge
case; deriving it from the same `organiser = true` filter that builds the dropdown means the row and
the dropdown can never disagree.

---

## §10 — Capping the done-inclusive view

**Decision**: Query `LIMIT 201`, render at most 200, and show the cut-off notice when 201 rows came
back.

**Rationale**: FR-028a needs both the cap and the knowledge of whether anything was cut off. Fetching
one extra row answers both in a single query — a separate `COUNT(*)` would double the round trips to
learn one boolean.

---

## §11 — Timestamp rendering in the secondary text

**Decision**: Render `Instant` with plain `th:text`, giving `Instant.toString()` — ISO-8601 in UTC.

**Rationale**: This is the established convention in this project; `organiser/audit/list.html`
renders `entry.occurredAt` exactly this way, and no template anywhere uses `#temporals` or a
`DateTimeFormatter`. Following it settles the one display question `/speckit-clarify` left
Outstanding, without inventing a formatting or timezone policy this codebase has never needed.

---

## §12 — Concurrency on the Task list

**Decision**: No stale-write guard. Done and reopen are conditional updates
(`... WHERE id = :id AND done = false`); zero rows affected is success, not an error. Assignee save
is a plain last-write-wins update.

**Rationale**: FR-027 requires marking an already-done Task done to be accepted without error, and
the "two Organisers edit the same Task" edge case explicitly specifies last-write-wins for the
assignee. This is the *opposite* of feature 007's FR-018 `updatedAt` guard on Event Destinations, and
deliberately so — that guard protects a configuration object where a silent overwrite loses work;
here the spec has decided a lost assignee edit is preferable to an error dialog. Worth stating
plainly so the difference between the two screens does not read as an oversight.

---

## Dependencies

**No new runtime or test dependency.** Everything this feature needs is already on the classpath:
Jackson (`jackson-databind`, `jackson-datatype-jsr310`) for §2/§3, R2DBC + Postgres for storage,
Thymeleaf for the views, and Testcontainers + `WebTestClient` + Playwright/axe for the tests.

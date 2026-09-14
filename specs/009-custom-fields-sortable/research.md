# Phase 0 Research: Sortable Custom Fields

No `NEEDS CLARIFICATION` markers remained in the Technical Context; the items below are the design decisions
that had more than one reasonable implementation, resolved against this codebase's existing patterns.

## 1. Persisting the index: an `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` in `schema.sql`, mirroring `content_pages.sort_index`

**Decision**: Append

```sql
ALTER TABLE custom_field_definitions ADD COLUMN IF NOT EXISTS sort_index integer NOT NULL DEFAULT 0;
```

to `src/main/resources/schema.sql`, and add `private int sortIndex;` to `CustomFieldDefinition` (Spring Data
R2DBC's default naming strategy maps it to `sort_index`, exactly as `ContentPage.sortIndex` already is).

**Rationale**: The project has no migration tool; every prior column addition to this exact table
(`public`, `overview`, `enabled` in feature 004) used this idempotent form. `DEFAULT 0` backfills every
pre-existing row (FR-002), and the seeded COUNTRY `INSERT ... WHERE NOT EXISTS` does not name the column, so it
also lands at 0. Because `ReactiveCrudRepository.save()` issues an INSERT naming every mapped column, a Java
`int` field guarantees a new definition is always written with an explicit index (0 unless the form set one) —
the DB default is a backstop for old rows only, same reasoning as the existing `enabled` comment in
`CustomFieldService.create`.

**Alternatives considered**:
- A separate `custom_field_ordering` table — rejected: over-engineering for one integer per row.
- `smallint` — rejected: the spec fixes the accepted range to signed 32-bit, and `content_pages` uses `integer`.

## 2. Where ordering lives: one `Comparator` applied in `CustomFieldService`, and every view reads through the service

**Decision**: Define a single ordering constant on the entity:

```java
Comparator<CustomFieldDefinition> DISPLAY_ORDER =
    Comparator.comparingInt(CustomFieldDefinition::getSortIndex)
        .thenComparing(CustomFieldDefinition::getLabel, String.CASE_INSENSITIVE_ORDER)
        .thenComparing(CustomFieldDefinition::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
```

`CustomFieldService.findAll()` becomes `definitionRepository.findAll().sort(DISPLAY_ORDER)`, and
`registrationFields()` filters that already-ordered stream. `ParticipantService` currently bypasses the
service in two places — `loadCustomFieldValueViews()` (organiser detail + viewer detail) and
`findDirectoryListing()` (directory columns) — both are re-pointed at `customFieldService.findAll()`.
`ComplianceController`, `CustomFieldController`, `RegistrationController`, and `ProfileController` already go
through the service and need no ordering change.

**Rationale**: SC-002 demands that *every* view agrees. Making the two upstream sources ordered means the
five downstream templates cannot disagree, and no template needs touching. `Flux.sort()` is a standard
non-blocking Reactor operator; the buffered list is at most a few dozen definitions. `String.CASE_INSENSITIVE_ORDER`
is the exact comparator `findDirectoryListing()` already uses for FR-027a's name ordering, so the app has one
notion of "alphabetical". `createdAt` as the last key implements the clarified identical-label tie-break
(oldest first) and, since `uuidv7()` ids are time-ordered too, matches insertion order. `nullsLast` guards
Mockito-built entities in unit tests that never set a timestamp.

**Alternatives considered**:
- A derived repository query `findAllByOrderBySortIndexAscLabelAscCreatedAtAsc()` (the `ContentPageRepository`
  pattern) — rejected: `ORDER BY label` is collation-dependent (case-sensitive under `C`, case-insensitive under
  `en_US.UTF-8`), so "apple" vs "Banana" (spec scenario 2.4) would behave differently across environments and
  Testcontainers images. A `@Query` with `lower(label)` fixes case but still diverges from the Java comparator
  the directory already uses.
- A `java.text.Collator` (locale-aware, accent-folding) — rejected for now: it would make Custom Field labels
  sort differently from Participant names on the same page, and the spec only mandates case-insensitivity.
  Accepted limitation, recorded here: a label starting with an umlaut/accented letter (e.g. "Ärzte") sorts
  after "Z" within its index group, as it does today for Participant names. Switching both to a `Collator` is a
  one-line follow-up if it ever matters.
- Sorting in each template with Thymeleaf `#lists.sort` — rejected: five copies of the rule, and Thymeleaf
  cannot express the case-insensitive multi-key comparator cleanly.

## 3. Service signatures: `create`/`update` gain an `int sortIndex`, applied outside every lock

**Decision**: `create(label, fieldType, required, optionLabels, public_, overview, int sortIndex)` and
`update(id, label, required, requestedFieldType, public_, overview, int sortIndex)`. In `update`, the index is
set unconditionally inside the same `Mono.defer` that sets label/required/flags — i.e. after the
`field_type`-lock guard has passed for *type changes only*, which it always does when no type change is
requested — so the index is editable on a type-locked field and on the COUNTRY row (FR-006), exactly like
`public`/`overview` are today.

**Rationale**: Follows the way 004 extended the same two methods with `public_`/`overview`. A primitive `int`
(not `Integer`) because the controller always resolves a value (blank → 0), so there is no "leave unchanged"
semantics to model — the edit form always posts the current value back.

**Alternatives considered**:
- A dedicated `setSortIndex(id, int)` route/method — rejected: the spec places the index on the existing form,
  and a separate route would need its own template affordance.
- Passing the whole form as a record — rejected: every other method here takes flat parameters; consistency
  wins for a one-parameter addition. All existing callers (`CustomFieldController` plus ~12 invocations in
  `CustomFieldServiceTest`) are updated in the same red step.

## 4. Input validation: strict parse in the controller, rejection via the existing error re-render

**Decision**: In `CustomFieldController.create/update`, read `form.getFirst("sort_index")`; `null`/blank → `0`;
otherwise `Integer.parseInt(trimmed)`. A `NumberFormatException` (non-numeric, decimal, or outside the 32-bit
range — `parseInt` covers all three) is turned into a `CustomFieldConflictException("Sort index must be a whole
number")` *before* the service is called, so it flows through the exact `onErrorResume(CustomFieldConflictException)`
branch that already re-renders the form with `error` set and status 200. The re-render now also carries
`sortIndex` (the raw submitted text is not echoed; the form shows the last valid value or 0) alongside the
existing label/type/required/options attributes. The template uses `<input type="number" step="1"
name="sort_index">` as a first line of defence; the server parse is the contract.

**Rationale**: FR-005 requires rejection with a message and no write. `ContentPageController.parseIntOrZero`
silently coerces, which would violate the spec, so the precedent is consciously not followed. Reusing the
existing exception type keeps the controller's error path count at one and matches
`contracts/custom-fields-and-country.md`'s "200 re-render for form conflicts" convention.

**Alternatives considered**:
- Bean Validation (`@Min`/`@Max` on a form object) — rejected: the controller reads raw form data via
  `ServerWebExchange.getFormData()` (WebFlux `@RequestParam` limitation documented in the controller); there is
  no bound form object to annotate.
- Returning 400 — rejected: every other form conflict on this controller is a 200 re-render; the ITs assert on
  that convention.

Note for tasks (not in scope to fix, but adjacent): the *create* error re-render today does not echo `public_`/
`overview` back into the form. Adding `sortIndex` to that re-render sits on the same lines; whether to also echo
the two flags is a two-line judgement call for the implementer and does not affect this feature's acceptance.

## 5. Views: two template edits, five templates untouched

**Decision**: `organiser/custom-fields/form.html` gains a labelled `Sort index` number input (pre-filled from the
`sortIndex` model attribute: `0` on `/new`, the stored value on `/{id}/edit`). `organiser/custom-fields/list.html`
gains a `Sort index` column placed right after `Label`, so the two ordering keys sit side by side (FR-010). `profile-fields-form.html`, `participants/list.html`, `participants/detail.html`,
`organiser/participants/detail.html`, and `organiser/compliance/form.html` are not modified: each iterates a
list produced by the (now ordered) service methods in §2, and `participants/list.html` derives its header row
from `rows[0].overviewValues`, which `loadFieldViews` builds by `concatMap` over the ordered definitions —
so header and cells stay aligned by construction (spec scenario 2.3).

**Rationale**: Minimal diff; no risk of one template drifting from another.

## 6. Test strategy (Constitution V)

**Decision** (red first, then green):
- `CustomFieldServiceTest`: `findAll()` orders index → case-insensitive label → createdAt; `registrationFields()`
  preserves that order; `create` persists the index; `update` changes the index on a type-locked field and on the
  COUNTRY row without touching the guards.
- `CustomFieldManagementIT` (Testcontainers): POST without `sort_index` stores 0; POST with `-5` stores -5; POST
  with `abc` → 200, error text present, no row created; edit form pre-fills the stored value; list renders rows
  in the expected order and shows the index; `sort_index` is updatable on the COUNTRY row.
- `ParticipantServiceTest`: existing stubs on `customFieldDefinitionRepository.findAll()` move to
  `customFieldService.findAll()`; add an assertion that directory columns and detail fields follow the service
  order (the service is a mock, so the test asserts pass-through, not re-sorting).
- `ParticipantsDirectoryManagementIT`, `ProfileManagementIT`, `ComplianceManagementIT`: one ordering assertion
  each on the rendered HTML (label positions via `indexOf`), using a fixture of three definitions with indices
  -1/0/3 and deliberately non-alphabetical creation order.

**Rationale**: Each acceptance scenario in the spec maps to one of the above; the IT set covers every view
class the spec enumerates (FR-008/FR-009) without duplicating the pure ordering logic, which lives in the unit
test.

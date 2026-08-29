---

description: "Task list for feature implementation"
---

# Tasks: Participant Registration Form, Profile Fields & Directory

**Input**: Design documents from `/specs/004-participant-registration-form/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Constitution Principle V (Test-First Development) is NON-NEGOTIABLE for this project — every task list below includes failing tests written before their implementation, per the Red-Green-Refactor cycle.

**Reactive verification**: Per Constitution Development Workflow #4, unit tests exercising a service's reactive
chain (any test asserting a `Mono`/`Flux` result where the chain composes more than one operator) MUST use
`StepVerifier`, not a blocking `.block()` call. This applies to every `*ServiceTest`/`*PolicyTest` task below.
`IsoCountryCatalog` (T008) is a plain synchronous method with no reactive chain — its test uses ordinary JUnit
assertions.

**Organization**: Tasks are grouped by user story (P1–P3 from spec.md) to enable independent implementation and
testing of each story. Within each story, code is grouped by business concept, matching plan.md's package
layout (the new `participants` audience-scoped web package, plus extensions to `customfield`,
`organisersettings`, `participant`, `organiser.customfield`, `organiser.settings`, `home`, `web`).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: Which user story this task belongs to (US1–US6)
- File paths are relative to the repository root

## Path Conventions

Single Maven/Spring Boot project (see [plan.md](plan.md) Project Structure), extending 002/003's layout:
- Main code: `src/main/java/net/fabcelhaft/hackathonorganiser/` — extended domain packages `customfield/`,
  `organisersettings/`, `participant/`; new participant-facing web package `participants/`; extended
  `home/`, `web/`, `organiser/customfield/`, `organiser/settings/`
- Templates: `src/main/resources/templates/participants/` (new), `fragments/` (new shared fields fragment +
  extended layout), extended `organiser/custom-fields/`, `organiser/settings/`, `home/`
- Static assets: `src/main/resources/static/js/country-select.js` (new)
- Tests: `src/test/java/net/fabcelhaft/hackathonorganiser/`, mirroring the same packages, plus new
  `participants/` test package and two new `a11y/*IT` classes

No new dependency is added by this feature (Constitution Check, plan.md) — the ISO 3166 list comes from the
JDK's `java.util.Locale`/`Locale.of(...)` (not the deprecated two-arg `Locale` constructor), and the
registration-capacity race is closed with `TransactionalOperator` (already auto-configured by
`spring-boot-starter-data-r2dbc`) plus a native Postgres advisory lock — so there is no Setup phase; work
begins directly at Foundational.

---

## Phase 1: Foundational (Blocking Prerequisites)

**Purpose**: Schema and entity extensions genuinely shared by every later user story: the new `CustomFieldType`
values and visibility/enabled columns (read by registration, self-edit, and the directory alike), the new
`OrganiserSettings` fields (read by the capacity check, self-edit gate, skill-visibility resolution, and
directory-audience check), and the JDK-backed country catalog.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Tests (write first, confirm they fail)

- [ ] T001 [P] Write failing unit tests for `IsoCountryCatalog.all()` (returns one entry per ISO 3166-1
      alpha-2 code including `"PS"`/`"DE"`/`"US"`, sorted by display name, built via `Locale.of(...)` not the
      deprecated `new Locale(String, String)` constructor — plain JUnit, no `StepVerifier`, research.md §1) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/customfield/IsoCountryCatalogTest.java`
- [ ] T002 [P] Write failing unit tests for `OrganiserSettingsService.update(...)`'s four new parameters
      (`maxRegistrations`: `null` leaves unchanged, a positive value is accepted, `0`/negative raises
      `OrganiserSettingsConflictException` with no field changed at all — FR-007; `selfEditEnabled`,
      `skillVisibilityEnabled`, `participantsDirectoryAudience` each settable independently and left unchanged
      when `null`; verified via `StepVerifier`) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/organisersettings/OrganiserSettingsServiceTest.java`
      (extends the existing file)
- [ ] T003 [P] Write failing unit tests for `CustomFieldService.registrationFields()` (returns every
      non-`COUNTRY` definition plus the `COUNTRY` definition only when its `enabled` column is `true`;
      excludes it when `false`; verified via `StepVerifier`) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/customfield/CustomFieldServiceTest.java` (extends the
      existing file)

### Implementation

- [ ] T004 Add `public boolean NOT NULL DEFAULT false`, `overview boolean NOT NULL DEFAULT false`, and
      `enabled boolean NOT NULL DEFAULT true` columns to `custom_field_definitions` via
      `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`; add the partial unique index
      `custom_field_definitions_country_key ON custom_field_definitions (field_type) WHERE field_type =
      'COUNTRY'`; add the idempotent seed `INSERT ... SELECT 'Country', 'COUNTRY', false, false, false, false
      WHERE NOT EXISTS (...)` in `src/main/resources/schema.sql` (data-model.md "Schema additions",
      research.md §1)
- [ ] T005 Add `max_registrations integer` (with a `CHECK (max_registrations IS NULL OR max_registrations >=
      1)` constraint, added idempotently since PostgreSQL's `ADD CONSTRAINT` has no native `IF NOT EXISTS` —
      guard it in a `DO $$ ... IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = '...') ... $$`
      block), `self_edit_enabled boolean NOT NULL DEFAULT true`, `skill_visibility_enabled boolean NOT NULL
      DEFAULT false`, and `participants_directory_audience text NOT NULL DEFAULT 'ORGANISERS_ONLY'` columns to
      `organiser_settings` via `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` in
      `src/main/resources/schema.sql` (data-model.md, research.md §5), depends on T004 (same file)
- [ ] T006 [P] Add `SINGLE_SELECT` and `COUNTRY` values to the `CustomFieldType` enum in
      `src/main/java/net/fabcelhaft/hackathonorganiser/customfield/CustomFieldType.java`
- [ ] T007 [P] Add `public`, `overview`, `enabled` fields and accessors to `CustomFieldDefinition` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/customfield/CustomFieldDefinition.java`
- [ ] T008 [P] Create `IsoCountryCatalog` (a `record Country(String code, String name)` plus a static
      `List<Country> all()` built from `Locale.getISOCountries()` + `Locale.of("", code)
      .getDisplayCountry(Locale.ENGLISH)`, sorted by name, research.md §1) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/customfield/IsoCountryCatalog.java` — makes T001 pass
- [ ] T009 [P] Create the `DirectoryAudience` enum (`ORGANISERS_ONLY`, `ORGANISERS_AND_PARTICIPANTS`,
      `ALL_AUTHENTICATED`) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organisersettings/DirectoryAudience.java`
- [ ] T010 [P] Add `maxRegistrations` (`Integer`, nullable), `selfEditEnabled`, `skillVisibilityEnabled`
      (`boolean`), and `participantsDirectoryAudience` (`DirectoryAudience`) fields and accessors to
      `OrganiserSettings` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organisersettings/OrganiserSettings.java`, depends on
      T009
- [ ] T011 [P] Create `OrganiserSettingsConflictException` (mirrors `CustomFieldConflictException`'s existing
      shape) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organisersettings/OrganiserSettingsConflictException.java`
- [ ] T012 [P] Create `RegistrationCapacityReachedException` (mirrors `ParticipantConflictException`'s existing
      shape — kept distinct so callers can render FR-035's specific capacity message rather than a generic
      validation error) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/participant/RegistrationCapacityReachedException.java`
- [ ] T013 Extend `OrganiserSettingsService.update(...)` with four new parameters (`Integer maxRegistrations`,
      `Boolean selfEditEnabled`, `Boolean skillVisibilityEnabled`, `DirectoryAudience
      participantsDirectoryAudience`), each following the existing `null` = "leave unchanged" convention;
      validate `maxRegistrations` (`null` or `>= 1`) before touching any field, raising
      `OrganiserSettingsConflictException` and applying **no** change (to any of the seven now-total fields) on
      an invalid value (FR-007) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organisersettings/OrganiserSettingsService.java`,
      depends on T010, T011 — makes T002 pass
- [ ] T014 Implement `CustomFieldService.registrationFields()` (all definitions with `field_type != COUNTRY`,
      plus the `COUNTRY` definition only if `enabled = true`) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/customfield/CustomFieldService.java`, depends on T007 —
      makes T003 pass

**Checkpoint**: Foundation ready — user story implementation can now begin in priority order.

---

## Phase 2: User Story 1 - Register Through a Profile Form (Priority: P1) 🎯 MVP

**Goal**: An authenticated user with no Participant record fills in every configured Custom Field (including
`SINGLE_SELECT` and, if enabled, `COUNTRY`) and their Skills on a dedicated form, and submitting it creates a
registered Participant record carrying exactly what was entered.

**Independent Test**: Open registration as an unregistered user, submit without a required field (rejected, no
record created), then submit fully filled in (Participant record created with exactly those values).

**Depends on**: Foundational (registration fields catalog, Country catalog, `CustomFieldDefinition.enabled`).

### Tests for User Story 1 ⚠️ write first, confirm they fail

- [ ] T015 [P] [US1] Write failing `WebTestClient` integration tests: `GET /register` renders every
      `registrationFields()` entry and the Skill catalog as one flat set of ordinary fields — each by its own
      label and control, no "Custom Field" heading/tag/badge (FR-002a) — with required fields visually marked
      (FR-002); `POST /register` missing a required field re-renders the form (200) identifying the missing
      field(s), creating no Participant record (FR-003); a valid `POST /register` creates an `ACTIVE`
      Participant record carrying exactly the submitted values (FR-005) and redirects home with an explicit
      success confirmation (FR-033); a `SINGLE_SELECT` field restricts submission to exactly one of its
      options; the `COUNTRY` field (once enabled) accepts exactly one full-ISO-3166 code via its searchable
      control and rejects any other string; a submission with zero Skills selected succeeds (FR-004);
      abandoning the form (no submit) leaves no Participant record and registration remains offered; a
      Participant with status `NOT_PARTICIPATED` sees no Register/Reactivate entry point and no Revoke action,
      with an explanatory Organiser-set message (FR-006a) — per
      [contracts/registration-and-self-edit.md](contracts/registration-and-self-edit.md) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/participants/RegistrationManagementIT.java`
- [ ] T016 [P] [US1] Write failing unit tests for `ParticipantService.submitRegistration` (creates a new
      `ACTIVE` record with exactly the submitted Custom Field values and Skill selections, all written inside
      one `TransactionalOperator` transaction so a rejected submission leaves **no** partial write — assert via
      an intentionally-invalid submission that no `custom_field_values`/`participant_skills` rows are written;
      rejects a missing required field per its type — `FREE_TEXT` blank, `SINGLE_SELECT`/`COUNTRY` with zero
      selections, `MULTI_SELECT` with zero selections — with no record created; rejects more than one option
      for a `SINGLE_SELECT` field; rejects a `COUNTRY` value not present in `IsoCountryCatalog.all()`; accepts
      zero Skills; reactivates an existing `REVOKED` record in place — same id, not a new row — pre-filling
      from and then overwriting its stored values) and for the `NOT_PARTICIPATED` lockout guard now added to
      `selfRegister`/`selfRevoke` (both reject with `ParticipantConflictException` when the caller's current
      status is `NOT_PARTICIPATED`, FR-006a) — verified via `StepVerifier` — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/participant/ParticipantServiceTest.java` (extends the
      existing file)

### Implementation for User Story 1

- [ ] T017 [US1] Implement `ParticipantService.submitRegistration(UUID userId, ProfileFormSubmission
      submission)` (data-model.md "Participant"): inside one `TransactionalOperator.transactional(...)` chain,
      validate every `registrationFields()` entry's submitted answer against its type and `required` flag,
      validate `SINGLE_SELECT`/`COUNTRY` cardinality (≤1) and `COUNTRY` code membership, then create a new
      `ACTIVE` record or reactivate an existing non-`ACTIVE` one (retaining 003's existing branch-on-status
      logic) and write Custom Field values (reusing `custom_field_value_options` for `SINGLE_SELECT`,
      `custom_field_values.free_text_value` for `COUNTRY`, per data-model.md) and replace Skill selections, all
      in the same transaction; add the `NOT_PARTICIPATED` guard to `selfRegister` and `selfRevoke` (FR-006a)
      in `src/main/java/net/fabcelhaft/hackathonorganiser/participant/ParticipantService.java`, depends on
      T014, T008 — makes T016 pass
- [ ] T018 [US1] Implement `RegistrationController` (`GET`/`POST /register`) per
      [contracts/registration-and-self-edit.md](contracts/registration-and-self-edit.md): `GET` renders the
      form (pre-filled from an existing `REVOKED` record if present) or redirects home with a flash if the
      caller is `NOT_PARTICIPATED` or self-registration is disabled; `POST` calls `submitRegistration`,
      redirecting home with a success flash or re-rendering the form (200) with field-level errors on
      `ParticipantConflictException`, preserving submitted values — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/participants/RegistrationController.java`, depends on
      T017
- [ ] T019 [US1] Update `HomeController`/`home/index.html`: the "Register" action becomes a plain link to
      `GET /register` instead of an immediate `POST /register` (FR-001); render the `NOT_PARTICIPATED`
      explanatory message (FR-006a) in place of both Register and Revoke when applicable — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/home/HomeController.java` and
      `src/main/resources/templates/home/index.html`, depends on T017
- [ ] T020 [P] [US1] Create the shared fragment `src/main/resources/templates/fragments/profile-fields-form.html`
      (one block per `registrationFields()` entry — label + required/optional marker (FR-002) + a control
      matching its type: text input, radio/checkbox group for `SINGLE_SELECT`/`MULTI_SELECT`, and an accessible
      combobox for `COUNTRY` — plus the Skill multi-select; every control has a programmatically associated
      label, FR-039, and is keyboard-operable with a visible focus indicator, FR-038)
- [ ] T021 [P] [US1] Create `src/main/resources/templates/participants/register.html` (extends
      `fragments/layout.html`, includes `profile-fields-form.html`; a placeholder region for User Story 2's
      capacity message; submit control disabled for the duration of submission, FR-036), depends on T020
- [ ] T022 [P] [US1] Create `src/main/resources/static/js/country-select.js` (vanilla JS: filters the
      server-rendered country `role="listbox"` as the user types in the `role="combobox"` text input, updates
      `aria-expanded`/`aria-activedescendant`, and writes the chosen alpha-2 code into the hidden submit field
      — FR-045, research.md §1)

**Checkpoint**: User Story 1 is fully functional and testable independently —
`mvn verify -Dit.test=RegistrationManagementIT`.

---

## Phase 3: User Story 2 - Organiser Caps Total Registrations (Priority: P1)

**Goal**: An Organiser-configured maximum on `ACTIVE` registrations is enforced at submission time, race-safe
under concurrent attempts, with a clear capacity message shown before and during registration.

**Independent Test**: Set a small max, register that many Participants, confirm the next attempt is blocked,
revoke one, confirm registration becomes possible again.

**Depends on**: User Story 1 (the `submitRegistration`/`RegistrationController` path this wraps a capacity
gate around).

### Tests for User Story 2 ⚠️ write first, confirm they fail

- [ ] T023 [P] [US2] Write failing `WebTestClient` integration test additions: with `maxRegistrations` set,
      reaching it makes `GET /register` show "Maximum registrations reached" instead of the form (FR-010) and
      makes `POST /register` reject with the same distinct message (FR-035), for both a first-time registration
      and a `REVOKED`-Participant reactivation (FR-009); revoking one Participant when at capacity permits the
      next registration (FR-008); a `REVOKED` Participant never counts toward the max (FR-008); no configured
      max never blocks (FR-007) — per
      [contracts/registration-and-self-edit.md](contracts/registration-and-self-edit.md) — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/participants/RegistrationManagementIT.java`
- [ ] T024 [P] [US2] Write failing unit test for `ParticipantService.submitRegistration`'s capacity check
      (rejects with `RegistrationCapacityReachedException` at/over `maxRegistrations`, counting only `ACTIVE`
      Participants, before any write happens; accepts below the max; a `null` max never blocks; two concurrent
      calls for the last slot — driven via two subscriptions racing on the same `TransactionalOperator`-backed
      Mono against a real Postgres connection, not mocked — result in exactly one success, Edge Cases) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/participant/ParticipantServiceTest.java` (extends the
      existing file; the concurrency assertion needs a real database, so it lives in this Mockito-based file
      only if backed by Testcontainers — otherwise move it into T023's `WebTestClient` IT, which already runs
      against Testcontainers Postgres)

### Implementation for User Story 2

- [ ] T025 [US2] Extend `ParticipantService.submitRegistration` to, as the first step inside its existing
      transaction, execute `SELECT pg_advisory_xact_lock(hashtext('participant-registration-cap'))`, then
      `COUNT(*)` `ACTIVE` Participants and compare against `organiserSettings.maxRegistrations`, raising
      `RegistrationCapacityReachedException` (no write performed) when at or over the limit — research.md §4
      — in `src/main/java/net/fabcelhaft/hackathonorganiser/participant/ParticipantService.java`, depends on
      T017 — makes T024 pass
- [ ] T026 [US2] Extend `RegistrationController`: `GET /register` shows the capacity message instead of the
      form when `ParticipantService` reports the cap is reached; `POST /register` catches
      `RegistrationCapacityReachedException` and re-renders the same capacity message (200), distinct from
      field-validation errors — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/participants/RegistrationController.java`, depends on
      T025, T018 — makes T023 pass
- [ ] T027 [P] [US2] Extend `OrganiserSettingsController` and `organiser/settings/form.html`: add a "Maximum
      registrations" number input (blank = unlimited), submitted and validated atomically alongside the
      existing toggles (FR-007) — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/settings/OrganiserSettingsController.java` and
      `src/main/resources/templates/organiser/settings/form.html`, depends on T013
- [ ] T028 [P] [US2] Extend `src/main/resources/templates/participants/register.html`'s capacity-message
      placeholder (from T021) to render the actual "Maximum registrations reached" text in place of the field
      fragment (FR-010)

**Checkpoint**: User Stories 1–2 both work independently —
`mvn verify -Dit.test=RegistrationManagementIT,SettingsManagementIT`.

---

## Phase 4: User Story 3 - Organiser Configures Field Types & Visibility (Priority: P2)

**Goal**: An Organiser creates `SINGLE_SELECT` Custom Fields, enables/disables the built-in Country field, and
marks any field Public and/or Overview.

**Independent Test**: Create a `SINGLE_SELECT` field with options, enable Country, mark one field Public and
Overview, confirm those flags are honored on the registration form (User Story 1).

**Depends on**: Foundational (schema/entity extensions) and User Story 1 (whose registration form is what
"honored" means in this story's own Independent Test).

### Tests for User Story 3 ⚠️ write first, confirm they fail

- [ ] T029 [P] [US3] Write failing `WebTestClient` integration test additions: creating a `SINGLE_SELECT` field
      requires ≥1 option (mirrors the existing `MULTI_SELECT` rule, FR-012) and then appears on `GET /register`
      restricted to one choice; `Public`/`Overview` checkboxes are independently settable and persist (FR-016);
      enabling Country makes it appear on `GET /register` as a searchable single-select populated with the full
      ISO 3166 list and no organiser-editable options (FR-013); disabling it removes it from `GET /register`
      while an already-recorded Participant value for it remains visible on that Participant's existing
      organiser-managed detail record (FR-015); attempting to change the Country row's `field_type` or delete
      it is rejected (research.md §1); a non-Organiser is denied every route — per
      [contracts/custom-fields-and-country.md](contracts/custom-fields-and-country.md) — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/customfield/CustomFieldManagementIT.java`
- [ ] T030 [P] [US3] Write failing unit tests for `CustomFieldService`'s new behavior: `create()` rejects a
      `SINGLE_SELECT` submitted with zero options, the same way it already rejects `MULTI_SELECT`; `create()`
      and `deleteDefinition()` reject a `field_type = COUNTRY` argument outright; `update()` accepts `public`/
      `overview` independently with no lock, on any field type; `setCountryEnabled(boolean)` toggles the
      singleton `COUNTRY` row's `enabled` column — verified via `StepVerifier` — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/customfield/CustomFieldServiceTest.java`

### Implementation for User Story 3

- [ ] T031 [US3] Extend `CustomFieldService`: `create()` applies the ≥1-option rule to `SINGLE_SELECT` the same
      way it already does for `MULTI_SELECT`; `create()`/`deleteDefinition()` reject `field_type = COUNTRY`
      with `CustomFieldConflictException`; `update()` gains `Boolean public_`/`Boolean overview` parameters
      applied independently of the existing `field_type`-lock logic; add
      `setCountryEnabled(boolean enabled)` (looks up the singleton `COUNTRY` row and flips its `enabled`
      column) — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/customfield/CustomFieldService.java`, depends on T007,
      T014 — makes T030 pass
- [ ] T032 [US3] Extend `organiser/customfield/CustomFieldController`: `field_type` select on create gains
      `SINGLE_SELECT`; create/edit forms gain `public`/`overview` checkboxes; add
      `POST /organiser/custom-fields/{id}/country/enable` and `.../disable` per
      [contracts/custom-fields-and-country.md](contracts/custom-fields-and-country.md) — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/customfield/CustomFieldController.java`,
      depends on T031 — makes T029 pass
- [ ] T033 [P] [US3] Extend `src/main/resources/templates/organiser/custom-fields/list.html` and `form.html`:
      `SINGLE_SELECT` in the type dropdown (reusing the existing option-list input `MULTI_SELECT` already has);
      `Public`/`Overview` checkboxes; the `COUNTRY` row shows its `enabled` state and an Enable/Disable action
      in place of a type-change/delete control

**Checkpoint**: User Stories 1–3 all work independently —
`mvn verify -Dit.test=RegistrationManagementIT,SettingsManagementIT,CustomFieldManagementIT`.

---

## Phase 5: User Story 4 - Participants Edit Their Own Profile (Priority: P2)

**Goal**: When self-edit is enabled, a Participant reopens and updates their own Custom Field values and Skill
selections, validated the same way as registration; their own profile stays viewable read-only regardless.

**Independent Test**: Enable self-edit, change a field value and Skill selection, save, confirm it persists;
disable self-edit, confirm the edit action disappears.

**Depends on**: User Story 1 (`registrationFields()`-driven validation and the shared field-rendering
fragment this reuses).

### Tests for User Story 4 ⚠️ write first, confirm they fail

- [ ] T034 [P] [US4] Write failing `WebTestClient` integration tests: `GET /profile` always shows the caller's
      current values read-only, including when self-edit is disabled or status is `NOT_PARTICIPATED` (FR-023);
      an Edit link/action appears only when self-edit is currently enabled; `GET /profile/edit` is pre-filled
      with current values (FR-022); a valid `POST /profile/edit` persists changes with an explicit confirmation
      and the new values show on the next `GET /profile` (FR-022, FR-034); an invalid submission (missing
      required field, type-mismatched value) is rejected the same way registration is, with no partial save
      (FR-003, FR-022); self-edit disabled or status `NOT_PARTICIPATED` between page load and submission is
      rejected server-side regardless of what the page displayed (FR-024) — per
      [contracts/registration-and-self-edit.md](contracts/registration-and-self-edit.md) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/participants/ProfileManagementIT.java`
- [ ] T035 [P] [US4] Write failing unit tests for `ParticipantService.submitSelfEdit` (reuses
      `registrationFields()`'s validation rules identically to `submitRegistration`; rejects with
      `ParticipantConflictException` when `selfEditEnabled` is currently `false` or status is
      `NOT_PARTICIPATED`, re-read at call time, never cached; persists changes in place, no new record;
      verified via `StepVerifier`) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/participant/ParticipantServiceTest.java` (extends the
      existing file)

### Implementation for User Story 4

- [ ] T036 [US4] Implement `ParticipantService.submitSelfEdit(UUID participantId, ProfileFormSubmission
      submission)` (same per-field validation as `submitRegistration`, minus capacity/create-vs-reactivate
      branching; gated on `organiserSettings.selfEditEnabled` and the `NOT_PARTICIPATED` guard) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/participant/ParticipantService.java`, depends on T017,
      T013 — makes T035 pass
- [ ] T037 [US4] Implement `ProfileController` (`GET /profile` read-only; `GET`/`POST /profile/edit`) per
      [contracts/registration-and-self-edit.md](contracts/registration-and-self-edit.md) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/participants/ProfileController.java`, depends on T036 —
      makes T034 pass
- [ ] T038 [P] [US4] Create `src/main/resources/templates/participants/edit.html` (extends
      `fragments/layout.html`, includes `fragments/profile-fields-form.html` from T020, pre-filled)
- [ ] T039 [P] [US4] Create `src/main/resources/templates/participants/detail.html` — the "self" read-only
      rendering used by `GET /profile` for now (organiser/other-viewer modes are added in User Story 5); an
      Edit action shown only when self-edit is currently enabled
- [ ] T040 [P] [US4] Extend `OrganiserSettingsController` and `organiser/settings/form.html`: add an "Allow
      participants to edit their own profile" checkbox — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/settings/OrganiserSettingsController.java` and
      `src/main/resources/templates/organiser/settings/form.html`, depends on T013

**Checkpoint**: User Stories 1–4 all work independently —
`mvn verify -Dit.test=RegistrationManagementIT,SettingsManagementIT,CustomFieldManagementIT,ProfileManagementIT`.

---

## Phase 6: User Story 5 - Participants Directory & Detail View (Priority: P2)

**Goal**: A configurable-audience "Participants" nav item lists registered Participants in a table (Overview
columns, alphabetical by name) and links to a detail view resolving Public/Overview/organiser/self visibility.

**Independent Test**: Set the directory audience to "all authenticated users," confirm a Participant sees the
menu item and table, open another Participant's detail view, confirm only Public data is shown.

**Depends on**: User Story 1 (Participant profile data to list/show) and User Story 3 (the Public/Overview
flags this resolves).

### Tests for User Story 5 ⚠️ write first, confirm they fail

- [ ] T041 [P] [US5] Write failing `WebTestClient` integration tests: the "Participants" nav item is shown/
      hidden exactly per the configured audience (FR-025); `GET /participants` lists `ACTIVE` Participants
      alphabetically ascending by display name (FR-027a) with one column per Overview-marked Custom Field, no
      Skills column ever (FR-027), and a clear empty-value indicator for an unfilled cell (FR-031);
      `GET /participants/{id}` in self mode shows everything regardless of flags (FR-029); in organiser mode
      shows everything regardless of flags (FR-030); in other-viewer mode shows only `Public` fields, omitting
      an Overview-only-non-Public field entirely (FR-017, FR-019); both routes return 403 for a requester
      outside the configured audience, including via a direct link (FR-026, Edge Cases); changing the audience
      setting is enforced on the very next request, not retroactively on an open page (Edge Cases) — per
      [contracts/participants-directory.md](contracts/participants-directory.md) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/participants/ParticipantsDirectoryManagementIT.java`
- [ ] T042 [P] [US5] Write failing unit tests for `ParticipantsDirectoryAccessPolicy` (all three
      `DirectoryAudience` tiers combined with organiser / has-Participant-record / plain-authenticated-user,
      research.md §6) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/participants/ParticipantsDirectoryAccessPolicyTest.java`
- [ ] T043 [P] [US5] Write failing unit tests for `ParticipantService.findDirectoryListing()` (only `ACTIVE`
      Participants, ordered alphabetically ascending by display name, one value per Overview-marked field) and
      `findDetailForViewer(...)` (self/organiser see everything; other-viewer sees only `Public`, an
      Overview-only-non-Public field omitted per FR-017) — verified via `StepVerifier` — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/participant/ParticipantServiceTest.java`

### Implementation for User Story 5

- [ ] T044 [US5] Implement `ParticipantsDirectoryAccessPolicy` (one method resolving audience membership from
      `OrganiserSettings.participantsDirectoryAudience` plus `(isOrganiser, hasParticipantRecord)`, reused by
      both the controllers below and the nav model advice, research.md §6) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/participants/ParticipantsDirectoryAccessPolicy.java`,
      depends on T010 — makes T042 pass
- [ ] T045 [US5] Implement `ParticipantService.findDirectoryListing()` and `findDetailForViewer(UUID
      participantId, UUID viewerUserId, boolean viewerIsOrganiser)` (data-model.md, research.md §3) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/participant/ParticipantService.java`, depends on T007 —
      makes T043 pass
- [ ] T046 [US5] Implement `ParticipantsDirectoryController` (`GET /participants`, `GET /participants/{id}`)
      per [contracts/participants-directory.md](contracts/participants-directory.md), returning 403 when
      `ParticipantsDirectoryAccessPolicy` denies access and the requested id isn't the caller's own, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/participants/ParticipantsDirectoryController.java`,
      depends on T044, T045 — makes T041 pass (partial)
- [ ] T047 [US5] Update `ProfileController`'s `GET /profile` (T037) to delegate to the same detail-rendering
      logic `ParticipantsDirectoryController` uses for self mode, so there is exactly one detail-rendering code
      path (plan.md Structure Decision) — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/participants/ProfileController.java`, depends on T046
- [ ] T048 [P] [US5] Extend `CurrentUserModelAdvice` to inject `showParticipantsMenuItem` via
      `ParticipantsDirectoryAccessPolicy` — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/web/CurrentUserModelAdvice.java`, depends on T044
- [ ] T049 [P] [US5] Extend `src/main/resources/templates/fragments/layout.html`: a "Participants" nav item
      rendered only when `showParticipantsMenuItem` is true, depends on T048
- [ ] T050 [P] [US5] Create `src/main/resources/templates/participants/list.html` (directory table; Overview
      columns; a clear "—"/"Not provided" empty-cell indicator, FR-031)
- [ ] T051 [US5] Extend `src/main/resources/templates/participants/detail.html` (from T039) to add
      organiser-mode and other-viewer-mode rendering (self mode already exists), depends on T039, T045
- [ ] T052 [P] [US5] Extend `OrganiserSettingsController` and `organiser/settings/form.html`: add a
      "Participants directory visible to" radio group (Organisers only / Organisers and Participants / All
      authenticated users) — depends on T013

**Checkpoint**: User Stories 1–5 all work independently —
`mvn verify -Dit.test=RegistrationManagementIT,SettingsManagementIT,CustomFieldManagementIT,ProfileManagementIT,ParticipantsDirectoryManagementIT`.

---

## Phase 7: User Story 6 - Organiser Controls Skill Visibility & Values Are Clearly Marked (Priority: P3)

**Goal**: A global toggle governs whether Skills are visible to other users on the detail view, and every
field/Skill a Participant sees on their own profile is labeled "visible to others" or "private".

**Independent Test**: Toggle skill visibility off, confirm Skills disappear from other Participants' detail
views (never from the directory table); toggle on, confirm they reappear; open a Participant's own profile and
confirm each value's visible/private label matches its actual configuration.

**Depends on**: User Story 5 (the detail view this labels/filters) and User Story 4 (the self-edit page this
also labels).

### Tests for User Story 6 ⚠️ write first, confirm they fail

- [ ] T053 [P] [US6] Write failing `WebTestClient` integration test additions: enabling skill visibility shows
      Skills on another viewer's `GET /participants/{id}` (subject to the directory audience, FR-018/FR-019);
      disabling hides them from non-owning, non-organiser viewers; Skills never appear as a `GET /participants`
      table column regardless of the toggle (FR-027); on `GET /profile` and `GET /profile/edit`, every Custom
      Field value and the Skills section is labeled "visible to others" or "private" via text/icon, matching
      its actual `public`/skill-visibility configuration, never color alone (FR-020, FR-041) — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/participants/ParticipantsDirectoryManagementIT.java` and
      `src/test/java/net/fabcelhaft/hackathonorganiser/participants/ProfileManagementIT.java`
- [ ] T054 [P] [US6] Write failing unit tests for `ParticipantService.findDetailForViewer`'s Skill-visibility
      filtering (non-owning, non-organiser viewer sees Skills only when `skillVisibilityEnabled` is true;
      self/organiser always see them) and for a per-field/per-Skill "visible to others" boolean now included in
      the self-mode read model — verified via `StepVerifier` — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/participant/ParticipantServiceTest.java`

### Implementation for User Story 6

- [ ] T055 [US6] Extend `ParticipantService.findDetailForViewer` to filter Skills by
      `organiserSettings.skillVisibilityEnabled` for non-owning, non-organiser viewers, and to compute a
      per-Custom-Field-value and per-Skill "visible to others" boolean for self-mode labeling (FR-020) — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/participant/ParticipantService.java`, depends on T045 —
      makes T054 pass
- [ ] T056 [US6] Extend `participants/detail.html`, `edit.html`, and `fragments/profile-fields-form.html` to
      render the "visible to others"/"private" label (a text string plus an icon carrying an accessible text
      alternative, never color alone — FR-020, FR-041) next to each Custom Field and the Skills section in self
      mode — depends on T055, T020, T039, T051
- [ ] T057 [P] [US6] Extend `OrganiserSettingsController` and `organiser/settings/form.html`: add a "Show
      participants' skills to other users" checkbox — depends on T013

**Checkpoint**: All six user stories are independently functional.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: The automated accessibility gate (SC-009), which only makes sense once every screen it scans
exists, plus a final full-suite/manual verification pass.

- [ ] T058 [P] Write `a11y.RegistrationAccessibilityIT` (Playwright headless Chromium + Deque's `AxeBuilder`,
      `@SpringBootTest(webEnvironment = RANDOM_PORT)` + Testcontainers, reusing 003's already-justified
      tooling per research.md §7): scans the registration form (`GET /register`, including its capacity-message
      state) and the self-edit form (`GET /profile/edit`); asserts zero `critical`/`serious` WCAG 2.1 AA
      violations on each (SC-009) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/RegistrationAccessibilityIT.java`
- [ ] T059 [P] Write `a11y.ParticipantsDirectoryAccessibilityIT`: scans the Participants directory table, a
      Participant's own detail view (`GET /profile`), another Participant's detail view, and the extended
      organiser settings and Custom Field forms; asserts zero `critical`/`serious` WCAG 2.1 AA violations on
      each (SC-009); explicitly excludes 002/003's pre-existing organiser screens per FR-037's scope note — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/ParticipantsDirectoryAccessibilityIT.java`
- [ ] T060 Review T058/T059's results and adjust `src/main/resources/static/css/app.css` as needed so the
      required/optional marker, the visible/private badge, the capacity message, and the Country combobox's
      listbox popover meet WCAG 2.1 AA contrast minimums (4.5:1 normal text, 3:1 large text/UI component
      boundaries) in both light and dark presentation (FR-044)
- [ ] T061 Run `mvn verify` (full unit + `*ManagementIT`/`*IT` + `a11y.*IT` suite) and perform the
      [quickstart.md](quickstart.md) manual visual smoke test end-to-end across all six user stories
      (Constitution Development Workflow #3)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundational (Phase 1)**: No dependencies — start immediately. BLOCKS all user stories (every story reads
  the `CustomFieldDefinition`/`OrganiserSettings` extensions and/or `IsoCountryCatalog` introduced here).
- **User Stories (Phase 2–7)**: All depend on Foundational. Priority order per spec.md is US1 → US2 → US3 →
  US4 → US5 → US6; US2 extends US1's `submitRegistration`/`RegistrationController` with a capacity gate; US3
  is independently buildable right after Foundational but its own Independent Test references US1's
  registration form; US4 reuses US1's validation and shared field fragment; US5 reuses US1's Participant data
  and US3's visibility flags; US6 decorates US4's and US5's screens and filters US5's detail view.
- **Polish (Phase 8)**: Depends on all six user stories being complete (T058/T059 need every in-scope screen
  to exist).

### Within Each User Story

- Tests written and confirmed failing before implementation (Constitution Principle V, NON-NEGOTIABLE).
- Entities/enums/exceptions before services; services before controllers; controllers before templates that
  call them.
- Each story's Checkpoint is reachable via its own `mvn verify -Dit.test=...` run before starting the next.

### Parallel Opportunities

- Foundational's T001–T003 (tests) and T006–T012 (different files) can each run in parallel within their own
  group.
- Once Foundational is done, US1 must go first (every later story either extends its code paths or references
  its screens in its own Independent Test), but US3's Custom-Field-management work has no code dependency on
  US2's capacity gate and could be staffed in parallel with US2 once US1 lands, if team capacity allows.
- Within any story, tasks marked `[P]` touch different files and have no incomplete-task dependency.

---

## Parallel Example: User Story 1

```bash
# Tests together:
Task: "Write failing WebTestClient integration tests for User Story 1 in participants/RegistrationManagementIT.java"
Task: "Write failing unit tests for ParticipantService.submitRegistration + NOT_PARTICIPATED guards in participant/ParticipantServiceTest.java"

# Templates + script together (once RegistrationController exists):
Task: "Create fragments/profile-fields-form.html"
Task: "Create participants/register.html"
Task: "Create static/js/country-select.js"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1 (Foundational).
2. Complete Phase 2 (User Story 1).
3. **STOP and VALIDATE**: `mvn verify -Dit.test=RegistrationManagementIT` — an unregistered user can fill in
   every configured field and Skill and submit into a real Participant record, with correct required-field
   rejection.
4. Demo if ready — this alone delivers SC-001, the feature's core form-driven registration promise.

### Incremental Delivery

1. Foundational → ready.
2. US1 → validate independently → demo (registration form works end-to-end).
3. US2 → validate independently → demo (Organiser can cap total registrations, race-safe).
4. US3 → validate independently → demo (Organiser adds single-select fields, Country, visibility flags).
5. US4 → validate independently → demo (Participants edit their own profile).
6. US5 → validate independently → demo (Participants directory + detail view).
7. US6 → validate independently → demo (skill-visibility toggle, visible/private labeling) — full feature
   complete.
8. Polish → automated accessibility gate, full-suite + quickstart confirmation.

### Parallel Team Strategy

1. Team completes Foundational together.
2. Developer A takes US1 → US2 in sequence (real dependency); Developer B starts US3 once US1 lands (only a
   soft dependency via its own Independent Test), then takes US4 → US5 → US6 in sequence.
3. Polish once all six stories are merged.

---

## Notes

- `[P]` tasks touch different files and have no incomplete-task dependency.
- `[Story]` label maps each task to its user story for traceability back to spec.md.
- No new tables are introduced (data-model.md) — every extension lands on `custom_field_definitions` or
  `organiser_settings`; `custom_field_value_options` and `custom_field_values.free_text_value` are reused, not
  widened, for `SINGLE_SELECT`/`COUNTRY` values (research.md §1, §2).
- The registration-capacity race (T024, T025) is closed with a Postgres advisory lock inside a
  `TransactionalOperator` transaction, not a new dependency (research.md §4) — verify this with a real
  concurrent-request test against Testcontainers Postgres, not two mocked calls, since the race only exists at
  the database level.
- `ParticipantsDirectoryAccessPolicy` (T044) is the single source of truth for the configurable audience check,
  called by both the directory controllers and the nav model advice (T048) so the menu item and enforced access
  can never drift apart (research.md §6).
- Verify each story's tests fail before implementing it; commit after each task or logical group.
- Stop at any checkpoint to validate a story independently before moving on.

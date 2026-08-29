# Phase 0 Research: Participant Registration Form, Profile Fields & Directory

The feature spec's own Clarifications session already resolved every open product question, so there are no
`NEEDS CLARIFICATION` markers in Technical Context. What remains is a set of implementation-technology and
data-modeling decisions this feature needs that 002/003 never had to make (a new field type family, a
built-in reference-data field, a race-safe capacity cap, and how to enforce a *configurable* audience rather
than a fixed role). Each entry follows Decision / Rationale / Alternatives considered.

## 1. The Country field: built-in, toggleable, zero new dependency (FR-013–FR-015)

**Decision**: Add two new `CustomFieldType` values — `SINGLE_SELECT` (organiser-defined options, exactly like
`MULTI_SELECT` but capped to one selection) and `COUNTRY` (fixed, system-maintained options, never
organiser-edited). Exactly one `COUNTRY`-typed row is seeded once in `schema.sql` (idempotent, same pattern as
003's singleton `organiser_settings`/homepage `content_pages` seed) and protected by a partial unique index —
`CREATE UNIQUE INDEX ... ON custom_field_definitions (field_type) WHERE field_type = 'COUNTRY'` — so at most
one can ever exist. `CustomFieldDefinition` gains an `enabled boolean NOT NULL DEFAULT true` column that is
*only ever toggled* for this one row (`CustomFieldService.setCountryEnabled(boolean)`); every other field type
has no create/enable distinction — its row existing *is* "enabled", and deleting it is the only way to remove
it, so `enabled` stays `true` and unused for them. `CustomFieldService.create()`/`deleteDefinition()` reject a
`field_type = COUNTRY` argument outright (that row is never created or deleted by an Organiser, only toggled
and configured). The country list itself comes from `java.util.Locale.getISOCountries()` (ISO 3166-1 alpha-2
codes) paired with `Locale.of("", code).getDisplayCountry(Locale.ENGLISH)` for the display name — `Locale.of(...)`
rather than the two-arg `new Locale(String, String)` constructor, which has been deprecated since Java 19 in
favor of the `of(...)` factory methods — wrapped in a
new `IsoCountryCatalog.all()` returning `List<Country(code, name)>` sorted by name — computed in memory, no
database table, no static asset to keep in sync. A Participant's selected country is stored as its alpha-2
code in `custom_field_values.free_text_value` (the same column `FREE_TEXT` values already use) rather than via
`custom_field_value_options`, since Country options are not rows in `custom_field_options` at all.

**Rationale**: The JDK's own `Locale` API already ships the full ISO 3166-1 country list with English display
names and needs zero maintenance or additional dependency (Constitution I: "minimise dependency surface") —
exactly the "system-maintained static reference data" the spec's Assumptions call for. Modeling Country as a
`CustomFieldDefinition` row (rather than e.g. a boolean flag elsewhere) is what lets FR-014 hold literally:
"otherwise configurable the same as any other Custom Field Definition (required, Public, Overview, subject to
self-edit)" — those flags already live on that table, so Country gets them for free with no special-casing in
the visibility/edit code paths. Storing its value in the existing `free_text_value` slot avoids widening
`custom_field_value_options`'s FK (which points at real `custom_field_options` rows) to also accept synthetic
country codes, which would blur that table's one clear meaning.

**Alternatives considered**:
- A country-list library (e.g. `com.neovisionaries:nv-i18n`) — rejected: the JDK already has this data;
  adding a dependency for data `java.util.Locale` already provides would violate Constitution I for no gain.
- A `country_options` reference table seeded from a static list — rejected: 250-ish rows of immutable
  reference data that never changes per-deployment is exactly what an in-memory JDK-backed catalog is for; a
  table would need its own migration/seeding path for zero behavioral benefit.
- A separate `OrganiserSettings.countryFieldEnabled` boolean instead of a per-definition `enabled` column —
  rejected: it cannot carry Country's own required/Public/Overview flags, which FR-014 explicitly requires
  Country to have "the same as any other Custom Field Definition"; those flags only make sense attached to a
  `custom_field_definitions` row.

## 2. Single-select values reuse `custom_field_value_options` (FR-011, FR-012)

**Decision**: No new table for `SINGLE_SELECT` values. `custom_field_value_options` (the existing
`MULTI_SELECT` child table) is reused verbatim; `ParticipantService`'s value-persistence path enforces "at
most one selected option" for a `SINGLE_SELECT` definition as a business rule before writing (reject with
`ParticipantConflictException` if the caller submits more than one `optionId` for a `SINGLE_SELECT` field),
the same way `CustomFieldService.create()` already enforces "MULTI_SELECT needs ≥1 option" as a business rule
rather than a schema constraint.

**Rationale**: The composite-key shape (`participant_id`, `custom_field_definition_id`, `custom_field_option_id`)
is identical for one selection or many; a `SINGLE_SELECT`-only table would duplicate the schema and every
read/write query in `CustomFieldService`/`ParticipantService` for no structural difference. This mirrors
002/003's established preference (documented in `CustomFieldService`'s own Javadoc) for expressing
cardinality rules as service-layer checks rather than new schema shapes when the underlying storage need is
identical.

**Alternatives considered**: A `selected_option_id` nullable column directly on `custom_field_values` for
`SINGLE_SELECT`/`COUNTRY` — rejected for `SINGLE_SELECT` specifically because it would mean two different
storage locations for conceptually the same "which option(s) did they pick" data depending on cardinality,
complicating every read path (`loadCustomFieldValueViews` would need to branch on field type to know which
column to read); `COUNTRY` doesn't use this option-table mechanism at all per §1, so this alternative was
rejected there too, on the grounds already given.

## 3. Field visibility flags: two independent booleans on the existing table (FR-016, FR-017)

**Decision**: `custom_field_definitions` gains `public boolean NOT NULL DEFAULT false` and
`overview boolean NOT NULL DEFAULT false`, both independently settable via the same organiser edit form that
already sets `label`/`required`. No enum or bitmask — two plain booleans, since FR-016 explicitly requires
them "independently toggleable" and there are only four combinations, all meaningful (per FR-017: Overview
without Public still shows the column to Organisers/the owning Participant, never to anyone else). Read-side
resolution (“what does *this* viewer see for *this* Participant”) is computed in `ParticipantService`, not in
SQL: given `(definition.public, definition.overview, viewerIsOrganiser, viewerIsOwner)`, the value is visible
iff `definition.public || viewerIsOrganiser || viewerIsOwner`; the directory table additionally requires
`definition.overview` to render a column for that field at all (FR-027).

**Rationale**: This is the same "closed set of small booleans, resolved in the service layer" approach 002
already uses for `required`/003 for its three settings toggles — introducing an enum for a 2-flag space would
be over-engineering for no expressiveness gained (Constitution guidance: no premature abstraction).

**Alternatives considered**: A single `visibility` enum (`PRIVATE` | `OVERVIEW_ONLY` | `PUBLIC`) — rejected:
collapses two genuinely independent flags into one, and FR-016's own wording ("the two flags being
independently toggleable") is explicit that they are not mutually exclusive tiers.

## 4. Registration-capacity race safety (FR-009, Edge Cases: simultaneous last-slot submissions)

**Decision**: Wrap the entire form-driven registration/reactivation write path — capacity check, Participant
status write, Custom Field value writes, Skill-selection replace — in one reactive transaction using Spring
Data R2DBC's auto-configured `TransactionalOperator` (no new dependency: `R2dbcTransactionManager` is
auto-configured the moment a `ConnectionFactory` bean exists, which 002 already established). Inside that
transaction, the very first statement is `SELECT pg_advisory_xact_lock(hashtext('participant-registration-cap'))`
— a session-scoped Postgres advisory lock, automatically released at transaction end (commit or rollback) —
which serializes every concurrent registration attempt through this one critical section. Only after acquiring
the lock does the transaction `COUNT(*)` current `ACTIVE` Participants and compare against
`organiser_settings.max_registrations`; if at capacity, the transaction rolls back and
`RegistrationCapacityReachedException` is raised (giving FR-035's distinct message) before any write happens.
This is the same transaction that also performs the Custom Field/Skill writes, so a rejected submission never
leaves a partial record (FR-003, Edge Cases), and a successful one is atomic end-to-end.

**Rationale**: An advisory lock scoped to a transaction is Postgres's own documented mechanism for exactly this
"serialize a critical section across concurrent transactions" need, requires no schema change, and needs no
new dependency — `TransactionalOperator` already ships with `spring-boot-starter-data-r2dbc`. A plain
`SELECT COUNT(*)` without a lock would be a classic TOCTOU race (two concurrent transactions both read "1
below max", both proceed, capacity is exceeded) — the exact scenario the spec's Edge Cases section calls out.

**Alternatives considered**:
- A `SERIALIZABLE` isolation level on the whole transaction, relying on Postgres to abort one of two
  conflicting transactions — rejected: correct in principle, but pushes retry-on-serialization-failure
  handling onto every caller of this path; the advisory lock instead makes the second submission simply wait
  its turn (no retry logic needed), which is simpler to reason about and test deterministically.
- A unique partial index like `groups_topic_id_active_key`'s pattern (e.g. limiting `ACTIVE` participant rows
  by some capacity-encoding column) — rejected: capacity is a *count* against a *configurable* threshold, not
  a fixed-cardinality invariant like "at most one active Group per Topic"; a unique index cannot express "at
  most N", only "at most 1".
- Application-level in-memory locking (e.g. a `Mono`-wrapped `Semaphore`) — rejected: does not hold across
  multiple application instances, and this codebase has no existing multi-instance-coordination mechanism;
  the database-level advisory lock is correct regardless of how many application instances are running.

## 5. Organiser Settings: four new fields on the existing singleton row (FR-007, FR-018, FR-021, FR-025)

**Decision**: Extend `organiser_settings` (not a new table) with `max_registrations integer` (nullable = no
limit; `CHECK (max_registrations IS NULL OR max_registrations >= 1)` enforcing FR-007 at the database level as
a concurrency-safe backstop, mirroring how existing unique indexes back up service-layer checks elsewhere),
`self_edit_enabled boolean NOT NULL DEFAULT true` (FR-021; no stated spec default, so it follows 003's
existing self-registration/self-revocation toggles in defaulting to enabled), `skill_visibility_enabled
boolean NOT NULL DEFAULT false` (FR-018; the spec's own Assumptions state this explicitly), and
`participants_directory_audience text NOT NULL DEFAULT 'ORGANISERS_ONLY'` (FR-025; a new
`DirectoryAudience` enum — `ORGANISERS_ONLY` | `ORGANISERS_AND_PARTICIPANTS` | `ALL_AUTHENTICATED` — no spec-
stated default, so it defaults to the most restrictive option, consistent with the private-by-default posture
the spec already applies to Skill visibility and to Overview-without-Public fields).
`OrganiserSettingsService.update(...)` gains the corresponding parameters (still `null` = "leave unchanged",
matching the existing three-toggle convention) and rejects an invalid `maxRegistrations` with a new
`OrganiserSettingsConflictException` before the database CHECK constraint would ever need to fire.

**Rationale**: This is a singleton settings row already; every prior feature in this codebase has extended it
in place rather than introducing a second settings table (`topicApprovalRequired` was added to it in 003 the
same way). The CHECK constraint gives FR-007 a database-level guarantee independent of the service-layer
validation, the same defense-in-depth pattern 002/003 already use (unique indexes backing up pre-checks).

**Alternatives considered**: A separate `directory_settings`/`registration_settings` table — rejected as an
unjustified split of what remains one cohesive, single-event configuration surface; every existing consumer
(`OrganiserSettingsService.current()`) already reads the whole row in one query, and splitting it would only
add join complexity with no isolation benefit (there is exactly one row either way).

## 6. Configurable directory audience vs. `SecurityConfig`'s fixed `/organiser/**` role rule (FR-025, FR-026)

**Decision**: The Participants directory's audience is *not* a route pattern in `SecurityConfig` — it's a
runtime `OrganiserSettings` value that can be any of three tiers, one of which (`ALL_AUTHENTICATED`) already
matches the blanket `.anyExchange().authenticated()` default, while the other two are *narrower* than that
default. So the routes (`GET /participants`, `GET /participants/{id}`) stay under the default authenticated
rule in `SecurityConfig` (no new path-matcher entry), and a single new class,
`ParticipantsDirectoryAccessPolicy`, holds the actual audience check: given the current `OrganiserSettings`
and `(isOrganiser, hasParticipantRecord)` for the requesting user, it returns whether they're in the
configured audience. Both directory controllers call it and return `403 Forbidden` when it says no (Edge
Cases: direct-URL access must be denied exactly like the hidden menu item), and `CurrentUserModelAdvice` calls
the same policy to decide whether to inject `showParticipantsMenuItem = true` for the shared nav fragment —
one method, two call sites, so the enforced access and the visible menu item can never drift apart.

**Rationale**: `SecurityConfig`'s `/organiser/**` → `ROLE_ORGANISER` rule is a *static*, role-based gate; this
feature's audience is a *dynamic*, Organiser-configurable setting that can include or exclude Participants
specifically (a concept `SecurityConfig` has no vocabulary for — it only knows `ROLE_ORGANISER` vs.
authenticated). Re-reading `OrganiserSettingsService.current()` per request (never cached) is the same "no
staleness" approach already established for `selfRegistrationEnabled`/`selfRevocationEnabled` checks in
`ParticipantService`, and directly satisfies the Edge Case "the next request MUST enforce the new setting."

**Alternatives considered**: A custom `ReactiveAuthorizationManager` wired into `SecurityConfig` that queries
`OrganiserSettingsService` — rejected: `SecurityConfig`'s authorization rules are evaluated before the
reactive request pipeline that would let a `Mono`-returning settings lookup complete cleanly inside a
synchronous-looking `authorizeExchange` DSL callback; the codebase has no existing precedent for an async
authorization manager, and a plain service-layer check inside the controller (already the pattern for
self-registration/self-revocation gating) is simpler and consistent.

## 7. Reusing 003's Playwright + axe-core suite rather than adding new tooling (FR-037–045, SC-009)

**Decision**: No new test dependency. This feature's new screens (registration form, self-edit form,
Participants directory table and detail view, the extended organiser settings/custom-field forms) get new
`a11y.*IT` classes following the exact `HomepageAccessibilityIT` pattern 003 already established and already
justified in that feature's Complexity Tracking.

**Rationale**: 003 already carried the cost (and the justification) of introducing browser-based accessibility
testing to this codebase; reusing it here is the direct payoff of that investment, not a new decision.

**Alternatives considered**: None seriously considered — re-litigating an already-justified, already-merged
testing decision for the same kind of requirement (automated WCAG scanning of new SSR screens) would be pure
churn.

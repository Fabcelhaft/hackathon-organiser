# Phase 0 Research: Topic Management, Group Formation & Compliance

The feature spec's own Clarifications session already resolved every open product question, so there are no
`NEEDS CLARIFICATION` markers in Technical Context. What remains is a set of implementation-technology and
data-modeling decisions 002–004 never had to make (a race-safe capacity-and-override join, a multi-rule
compliance evaluator, and a cross-domain read model for two different Topic tables). Each entry follows
Decision / Rationale / Alternatives considered.

## 1. Reconciling FR-012's "below the Maximum" with FR-013a's "no longer exceeds" (FR-011–FR-013a)

**Decision**: The Maximum Group Members compliance rule is satisfied whenever `currentMemberCount <=
maxGroupMembers` (inclusive, "at or below"), not the strictly-less-than reading FR-012's own prose ("below the
Maximum") could suggest in isolation. This is a distinct concept from Home Page/join-blocking "fullness"
(FR-003b: `currentMemberCount >= maxGroupMembers`), which uses `>=` on purpose so a Group sitting exactly at
capacity stops accepting new joins. A Group can therefore be simultaneously **full** (FR-003b, no more joins)
and **compliant** (this rule, not over capacity) at the exact same member count — these are two independently
useful, deliberately different thresholds, not the same test written two ways.

**Rationale**: FR-013a is the more operationally precise of the two requirements — it describes the exact
transition being tested ("the Group's compliance MUST simply evaluate as not satisfying the Maximum rule ...
until its membership **no longer exceeds** the new value") and is the requirement with a full Acceptance
Scenario/Edge Case behind it (lowering Maximum below current membership). Reading FR-012's "below the Maximum"
as colloquial shorthand for "not over the Maximum" — rather than a stricter, separately-intended `<` test — is
the only interpretation under which FR-012 and FR-013a describe the same rule rather than two different ones.
The alternative (strict `<`) would mean a Group at exactly its target size is permanently flagged Not
Compliant the moment it reaches full capacity, which has no support anywhere else in the spec (SC-004/FR-014's
Compliant status is clearly meant to be the steady-state outcome of a well-formed, fully-staffed team) and
would make "full and compliant" mutually exclusive for every single-rule ruleset, which the Home Page's own
"fullest first" framing (SC-002) treats as the ordinary, expected end state for a Topic.

**Alternatives considered**: Implementing FR-012's literal "below" wording (`<`) throughout — rejected for the
reason above (would make a maximally-staffed Group permanently Not Compliant, contradicting the feature's own
framing of what a successful Topic looks like). Making the two thresholds (fullness vs. Maximum-rule
compliance) configurable independently — rejected: the spec defines exactly one Maximum Group Members value
(FR-011); introducing a second organiser-configurable number nobody asked for would be premature abstraction.

## 2. Join race safety: a per-Topic Postgres advisory lock, reusing 004's exact mechanism (Edge Cases, FR-013)

**Decision**: `GroupService` gains a single atomic entry point, `join(UUID topicId, UUID participantId)`,
wrapped in the same `TransactionalOperator` bean 004 introduced (`ParticipantService`'s
`TransactionalOperatorConfig`, unchanged, just newly reused). Its first statement is
`SELECT pg_advisory_xact_lock(hashtext('topic-join:' || :topicId))` — a session-scoped advisory lock keyed on
the *Topic* (not the Group), released automatically at transaction end. This single lock key covers **both**
race scenarios described in the spec's Edge Cases with one mechanism: (a) two Participants racing to be "first
joiner" of a Topic with no Group yet (today only weakly guarded by `groups_topic_id_active_key`'s partial
unique index, which turns a lost race into a raw constraint violation rather than a clean retry-or-reject), and
(b) two Participants racing for the last open slot on a Topic that already has a Group. After acquiring the
lock, the transaction re-reads the Topic's current active Group (if any) and its member count, re-reads
`OrganiserSettings.maxGroupMembers` and the Group's `complianceOverride` flag (fresh, never cached — FR-020c's
same freshness rule applied here), and only then either creates a new Group + first membership or adds a
membership to the existing one — reusing `GroupService.addMember`'s existing "one active Group per Participant"
guard (`group_members_participant_id_active_key`) unchanged inside the same transaction.

**Rationale**: This is not a new pattern — it is 004's registration-capacity mechanism (research.md §4 there),
applied to a second "at most N of a thing" business rule the codebase now has. Keying the lock on `topicId`
rather than the (possibly not-yet-existing) `groupId` means the exact same lock acquisition line runs whether
or not a Group already exists, so there is only one code path to reason about instead of two. A plain
`COUNT(*)` re-check without the lock would be the identical TOCTOU race 004 already identified and rejected.

**Alternatives considered**: A `SERIALIZABLE` transaction relying on Postgres to abort a losing transaction —
rejected for the same reason 004 rejected it: pushes retry-on-conflict handling onto the caller for no
simplicity gain over a lock that just makes the second request wait its turn. A partial unique index encoding
"at most `max_group_members` active rows per Topic" — rejected: 004's research.md §4 already established that a
unique index cannot express "at most N" for a *configurable* N, only "at most 1"; that reasoning applies
identically here. Locking per-`groupId` and handling the not-yet-exists case as a separate code path —
rejected: doubles the branching for no benefit, since the Topic's id is known and stable in both cases while
the Group's id, by definition, is not yet known in the first-joiner case.

## 3. Compliance Ruleset storage: extend the `organiser_settings` singleton, add one child table for diversity requirements (FR-011, FR-011a–d, Key Entities: Compliance Ruleset, Custom Field Diversity Requirement)

**Decision**: `organiser_settings` (003/004's singleton row) gains four columns: `max_group_members integer NOT
NULL DEFAULT 5` (FR-011c's "seed a default on first startup" — see rationale below for why a plain
`NOT NULL DEFAULT` column achieves this without a `CommandLineRunner`), `min_group_members integer` (nullable =
unset, FR-011 "optional"), `topic_joining_enabled boolean NOT NULL DEFAULT true` (FR-020d), and
`skill_display_mode text NOT NULL DEFAULT 'STILL_NEEDED_ONLY'` (new `SkillDisplayMode` enum, FR-017 — defaults
to the narrower/more-private-feeling option, consistent with 004's precedent of defaulting new visibility-ish
toggles to their more conservative value). Two `CHECK` constraints back FR-011a/b at the database level, mirroring
004's `organiser_settings_max_registrations_check` pattern: `max_group_members >= 1` and
`min_group_members IS NULL OR min_group_members <= max_group_members`. A new table,
`compliance_diversity_requirements` (own `uuidv7()` id — a real one-to-many collection, not a pure
association — one row per configured requirement: `custom_field_definition_id` FK, `minimum_distinct_values
integer` with `CHECK (>= 2)` backing FR-011d), holds the requirement list; a new partial-unique-index-style
guard — an *unconditional* unique index on `custom_field_definition_id` — caps it to at most one requirement
per Custom Field.

**Rationale**: Every prior feature in this codebase extends the singleton settings row in place for a new
instance-wide toggle rather than introducing a parallel settings table (research.md §5 of 004 makes this
argument at length for that feature's four fields; the same argument applies unchanged to `max_group_members`/
`min_group_members`/`topic_joining_enabled`/`skill_display_mode` — they are all single values with no
per-request variation). `NOT NULL DEFAULT 5` on the new `max_group_members` column is what actually
*implements* FR-011c ("seed one with a default... so a new deployment is never left without a configured
Maximum"): a fresh database creates `organiser_settings`'s singleton row (already seeded by 003's own `INSERT
... ON CONFLICT DO NOTHING`) with `max_group_members = 5` for free, and Postgres back-fills the same default
onto the *already-existing* singleton row on any upgrade from 002/003/004 (`ADD COLUMN ... NOT NULL DEFAULT`
is applied to every existing row, not just new ones) — so there is exactly one "first startup, no ruleset yet"
condition to handle, and the column definition alone handles it, with no `CommandLineRunner` or extra seed
`INSERT` needed (unlike 003's `content_pages`/004's `COUNTRY` row, which needed an explicit seed insert because
those are rows in a *new* table, not columns being added to an existing singleton row). The diversity
requirements need their own real one-to-many table (not another `DatabaseClient`-backed pure-association table
like `topic_skills`) because each row carries its own payload (`minimum_distinct_values`) beyond the mere
existence of the `custom_field_definition_id ↔ ruleset` link — exactly 002's own distinction between "pure
association" and "association carrying a payload" tables (data-model.md conventions, reused verbatim). The
uniqueness guard (at most one requirement per Custom Field) prevents an organiser from accidentally configuring
two different minimums for the same field, a state the spec's AND-logic wording never anticipates and which
would silently resolve to "the stricter of the two wins" if allowed — the unique index instead makes that
impossible to create in the first place, the same defense-in-depth role every other unique index in this
schema already plays.

**Alternatives considered**: A dedicated `compliance_rulesets` table holding `max`/`min` (with
`compliance_diversity_requirements.ruleset_id` pointing at it) instead of extending `organiser_settings`
directly — rejected: there is, by the spec's own words, exactly one ruleset ("configured per instance," Key
Entities), so a table that will only ever hold one row buys nothing over the singleton `organiser_settings` row
every other single-instance setting already lives on, and would need its own singleton-guaranteeing unique
index doing the same job `organiser_settings_singleton_key` already does. Allowing multiple diversity
requirements per Custom Field, evaluated with AND (effectively taking the max of the minimums) — rejected in
favor of the explicit unique index, per the rationale above.

## 4. Group compliance override: one boolean column on `groups`, no new table (FR-014, FR-015, FR-016)

**Decision**: `groups` gains `compliance_override boolean NOT NULL DEFAULT false` (Key Entities: "Carries an
optional Organiser-set compliance override flag"). `GroupService.setComplianceOverride(UUID groupId, boolean
override)` flips it; `ComplianceService.evaluate(...)` checks it first and short-circuits to `COMPLIANT_OVERRIDE`
before running any rule, and `GroupService.join(...)` (§2) reads it inside the same locked transaction to decide
whether the Maximum cap is enforced for that specific join attempt (FR-013, FR-015b).

**Rationale**: A single boolean is the entire shape of this requirement — it has no payload (no "who set it,
when, why" is asked for anywhere in the spec) — so a new table or an audit-log-style entity would be
unjustified complexity for a flag that is read in exactly two places (compliance display, capacity
enforcement). This mirrors `Topic.approvalStatus`'s shape: a small enum/boolean living directly on the row it
describes, not a separate approval-events table.

**Alternatives considered**: None seriously considered — the spec's own Key Entities section already describes
this as "an optional Organiser-set compliance override flag" (singular, on the Group), leaving no ambiguity to
resolve.

## 5. Compliance evaluation is computed at read time, never persisted (FR-012, FR-012a, FR-014)

**Decision**: A Group's `Compliant | Not Compliant | Compliant (Organiser Override) | No Group Yet` status
(FR-014) is never stored as a column — it is computed fresh on every read by a new `ComplianceService.evaluate
(Group group, List<UUID> memberParticipantIds)` in a new `compliance` domain package, reading the current
`OrganiserSettings.maxGroupMembers`/`minGroupMembers` and `ComplianceDiversityRequirement` rows every time (no
caching), then, for each configured diversity requirement, querying `custom_field_values` for the Group's
current members' recorded values for that field and counting distinct non-blank ones (FR-012a). "No Group Yet"
is not a `ComplianceStatus` produced by this method at all — it is the caller's own branch when
`GroupService.findActiveGroupForTopic(topicId)` completes empty, since there is no `Group` row to evaluate in
that case.

**Rationale**: Every rule input here (`maxGroupMembers`, `minGroupMembers`, the diversity requirement list, and
each member's own Custom Field values) can change independently and at any time, and FR-011/FR-017's "changing
[a setting] MUST take effect... with no deployment required, on the next view" pattern (already established by
003's `topicApprovalRequired`/004's four toggles) is exactly the guarantee a stored, event-driven status column
cannot give without also re-evaluating every Group on every settings change. Computing at read time is the
direct continuation of that established freshness guarantee, and it is cheap: the Topic Overview page already
needs each Group's member list for its participant-count column, so the same `group_members` read this
service needs is not new I/O, just a new interpretation of data already being fetched.

**Alternatives considered**: A stored `compliance_status` column on `groups`, recomputed by a service call
after every membership/settings change — rejected: every settings change (FR-011's ruleset edit) would need to
walk and rewrite every Group's row to stay accurate, an unbounded-fan-out write the spec never asks for and
that directly contradicts the "no deployment required" freshness bar already set by 003/004's simpler toggles.

## 6. Topic Skills on the self-service propose/edit path: extend the *existing* `topic_skills` machinery, not new tables or code (FR-001, FR-002; spec Assumptions)

**Decision**: `topic_skills` (002's association table) and its `DatabaseClient`-backed
`TopicService.replaceTopicSkills`/`loadSkills` helpers already exist and are already fully wired for the
**Organiser**-facing `TopicService.create`/`update` methods and `organiser/topics/form.html`. This feature's
genuinely new work is extending the two **self-service** methods that do not yet take a Skill list —
`TopicService.propose(UUID authorUserId, String name, String description)` and
`TopicService.updateAsAuthor(UUID id, UUID requesterUserId, String name, String description)` — to accept a
`List<UUID> skillIds` parameter each and call the same already-existing `replaceTopicSkills`/`allSkillIdsExist`
private helpers `create`/`update` already call, plus adding the same Skill multi-select control already proven
out in `organiser/topics/form.html` to `topics/form.html` (the self-service template). No schema change, no new
association table, no new read path — `TopicService.findDetail` already returns `skillIds` for a pre-fill.

**Rationale**: This is the textbook case for reusing an existing, already-tested pattern rather than writing a
parallel one — the storage shape, validation ("all selected skill ids must exist"), and replace-on-save
semantics are identical between "an Organiser edits a Topic's Skills" and "a Participant edits their own
Topic's Skills"; only the caller's authorization context differs, and that is already handled by
`updateAsAuthor`'s existing authorship check.

**Alternatives considered**: None — duplicating `replaceTopicSkills`/`loadSkills`/the multi-select markup for a
second, parallel "self-service Skill editing" code path would be pure, unjustified duplication of logic that
already exists and already works.

## 7. One new read-model service for both the Home Page table and the Topic Overview table (FR-003–FR-006, FR-014, FR-017)

**Decision**: A new `TopicDiscoveryService`, in the existing `topic` package, composes `TopicRepository`,
`GroupService` (member counts, active Group lookup), Skill loading (`topic_skills`/`participant_skills` via the
existing `DatabaseClient` helpers), `OrganiserSettingsService` (Skill Display Mode), and `ComplianceService`
(Topic Overview's compliance column only — the Home Page table has no compliance column per FR-004). It exposes
two read models: `findOpenTopicsForHomePage(UUID viewerParticipantIdOrNull, int limit)` (FR-003/FR-003a/FR-003b/
FR-004: Approved, not-full Topics only, fullest-first, capped, each row's Skills column intersected with the
viewer's own `participant_skills`) and `findTopicOverview(UUID viewerUserId, boolean viewerIsOrganiser)`
(FR-005/FR-006: every visible Topic — reusing `TopicService`'s existing `isVisibleTo` Pending-visibility rule —
each row's Skills column *not* intersected with the viewer, plus author display name and Compliance status).
`HomeController` replaces its current call to `TopicService.findVisibleTopicsFor` with the first method; a new
`topics.TopicOverviewController` (self-service package, since the page is available to every authenticated
user, not just Organisers) calls the second.

**Rationale**: Both tables need the same underlying "Topic + its active Group's member count + its Skills
after Skill Display Mode" computation and differ only in filtering (not-full-only vs. everything visible),
ordering (fullness vs. none specified), cap (10 vs. none), and two extra Topic-Overview-only columns (author,
compliance) — implementing them as two thin read-model methods on one service that already has every
dependency it needs (rather than duplicating the Skill-coverage/Group-lookup logic inside both `HomeController`
and a new controller directly) is the direct application of this codebase's existing pattern of pushing
read-model assembly into a service method returning a purpose-built record (`TopicService.TopicListView`,
`TopicService.TopicDetail`, `GroupService.GroupDetail` are all this same shape today).

**Alternatives considered**: Splitting Home Page and Topic Overview computation into two unrelated services —
rejected: would duplicate the "which Skills count as still-needed for this Topic's current Group" computation
(FR-017) in two places, the exact kind of near-identical logic this codebase's existing service-per-domain
(not per-page) organization is meant to avoid.

## 8. Custom Field diversity requirements extend the existing reference-blocking guard (Edge Cases: "removal MUST be blocked while any compliance rule still references it")

**Decision**: `CustomFieldService.deleteDefinition`'s existing `valueReferenceCount` guard (today summing
`custom_field_values` + `custom_field_value_options` counts, `SkillService.delete`'s twin) gains a third
`countReferencing("compliance_diversity_requirements", "custom_field_definition_id", id)` term in the same
`concatWith(...).reduce(0L, Long::sum)` chain, using the exact same `BadSqlGrammarException`-defensive
`countReferencing` private helper already defined on that class.

**Rationale**: This is the precise pattern the spec's own Edge Cases section points at ("consistent with the
core domain model's existing reference-blocking behavior for Custom Fields") — `CustomFieldService` already has
the guard, the helper, and the defensive "table might not exist yet" handling; adding one more term to an
existing `concatWith` chain is strictly smaller and more consistent than writing a second, parallel
reference-check method.

**Alternatives considered**: A separate guard method solely for the compliance case, called from a different
call site — rejected: `deleteDefinition` is already the single call site for "can this Custom Field Definition
be removed," and Custom Fields referenced by both a Participant value and a compliance rule must be blocked by
either reason, so one combined count is both simpler and correct by construction (an `OR`-of-reasons expressed
as a single non-zero sum, exactly like the existing two-source sum already does for Skills/Custom Field
values).

## 9. Reusing 003's Playwright + axe-core suite rather than adding new tooling (FR-021–FR-027, SC-008)

**Decision**: No new test dependency. This feature's new screens (Home Page's new 3-column topic table + Join
action, the Topic proposal/edit form's Skill picker, the Topic Overview table, the Organiser's compliance
settings screen, the Group compliance-override control, the Skill Display Mode toggle, the Topic-joining-enabled
toggle) get new `a11y.*IT` classes following the exact `HomepageAccessibilityIT` pattern 003 already established
and 004 already extended a second time.

**Rationale**: 003 already carried the cost (and the justification) of introducing browser-based accessibility
testing to this codebase, and 004 already demonstrated it scales cleanly to a second feature's worth of new
screens; reusing it a third time is the direct, expected payoff of that investment, not a new decision.

**Alternatives considered**: None seriously considered — re-litigating an already-justified, twice-reused
testing decision for the same kind of requirement would be pure churn.

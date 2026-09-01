# Phase 1 Data Model: Topic Management, Group Formation & Compliance

Continues 002–004's conventions (see those features' own data-model.md files): UUIDv7 PKs via Postgres's native
`uuid PRIMARY KEY DEFAULT uuidv7()` for every real entity table; `created_at`/`updated_at` `timestamptz NOT
NULL DEFAULT now()`; composite-key "pure association" tables manipulated via `DatabaseClient`, never a
single-`@Id` `ReactiveCrudRepository`; a real one-to-many collection with its own payload gets its own table
and repository instead (the same distinction 002 already draws between `topic_skills` and
`custom_field_options`). `schema.sql` stays the single source of DDL, `CREATE/ALTER ... IF NOT EXISTS`
throughout, rerun on every startup via `spring.sql.init.mode=always`. One new table
(`compliance_diversity_requirements`) is introduced; every other new concept extends an existing 002–004 table
(research.md §3–§4 explain why in each case).

## Modified Entities

### Topic (002's `topics` table — no schema change; FR-001, FR-002)

No new columns. Behavioral extension only, in `TopicService`:

| Method | Change |
|---|---|
| `propose(UUID authorUserId, String name, String description)` | **gains** a `List<UUID> skillIds` parameter (FR-001); validates and persists via the same `allSkillIdsExist`/`replaceTopicSkills` helpers `create`/`update` already use (research.md §6) |
| `updateAsAuthor(UUID id, UUID requesterUserId, String name, String description)` | **gains** a `List<UUID> skillIds` parameter (FR-002); same helpers, reusing the existing authorship check unchanged |

`findDetail`/`findVisibleTo`/`isVisibleTo` are unchanged — Story 1's Skill selections ride entirely on the
already-existing `topic_skills` table and `findDetail`'s already-existing `skillIds` field.

### Group (002's `groups` table — FR-014, FR-015, FR-016)

| Field | Type | Rules |
|---|---|---|
| `compliance_override` | boolean | **NEW**, `NOT NULL DEFAULT false` (Key Entities: "Carries an optional Organiser-set compliance override flag") — set/cleared only via `GroupService.setComplianceOverride(UUID groupId, boolean override)`, Organiser-only (FR-019) |

Behavioral additions, in `GroupService`:

- **`join(UUID topicId, UUID participantId)`** — the race-safe entry point for Story 3 (FR-007–FR-013a).
  Inside one `TransactionalOperator`-wrapped transaction (research.md §2): acquires
  `pg_advisory_xact_lock(hashtext('topic-join:' || topicId))`, re-reads the Topic's current active Group (if
  any) and its member count, re-reads `OrganiserSettings.maxGroupMembers` and (if a Group already exists) its
  `complianceOverride` flag, rejects with a friendly `TopicJoinConflictException` if the join would bring the
  member count to or beyond `maxGroupMembers` and no override is set (FR-013), then either creates a new
  `Group` (reusing `create`'s existing "no active Group yet for this Topic" path) or calls the existing
  `addMember` (reusing its "one active Group per Participant" guard, `group_members_participant_id_active_key`)
  against the existing one.
- **`setComplianceOverride(UUID groupId, boolean override)`** — FR-015/FR-016; completes empty (404) if
  `groupId` is unknown; no other guard (an Organiser may set or clear it regardless of current member count or
  automatic compliance outcome, per FR-015's own wording).
- **`activeMemberCount(UUID groupId)`** — a small read helper (`SELECT count(*) FROM group_members WHERE
  group_id = :gid AND active`) used by both `join` (capacity check) and `TopicDiscoveryService`/
  `ComplianceService` (participant-count columns, rule evaluation) so there is exactly one query shape for
  "how many active members does this Group have" across the whole feature.
- **`leave(UUID topicId, UUID participantId)`** — the race-safe entry point for Story 11 (FR-037–FR-037e,
  research.md §14). Inside the *same* `TransactionalOperator` + per-Topic advisory lock `join` already
  acquires: re-reads the Topic's active Group (empty → `GroupConflictException`, "not currently a member of
  this Topic"), calls the **already-existing** `removeMember(groupId, participantId)` (empty → same
  exception — the requester was not an active member), re-reads `activeMemberCount(groupId)`, and — only if
  it is now `0` — calls the **already-existing** `disband(groupId)` (FR-037c); otherwise returns the Group
  unchanged. No new SQL: both `removeMember` and `disband` are reused verbatim, unlike `join`, which needed a
  new capacity/override check `addMember` didn't have.

### Organiser Settings (003/004's `organiser_settings` singleton row — FR-011, FR-011a–d, FR-017, FR-020a, FR-020d)

| Field | Type | Rules |
|---|---|---|
| `max_group_members` | integer | **NEW**, `NOT NULL DEFAULT 5` (FR-011, FR-011b, FR-011c — research.md §3: the `DEFAULT` clause itself is what "seeds" every existing and future singleton row); `CHECK (max_group_members >= 1)` |
| `min_group_members` | integer | **NEW**, nullable = unset (FR-011, "optional"); `CHECK (min_group_members IS NULL OR min_group_members <= max_group_members)` (FR-011a) |
| `topic_joining_enabled` | boolean | **NEW**, `NOT NULL DEFAULT true` (FR-020a, FR-020d) |
| `skill_display_mode` | enum: `STILL_NEEDED_ONLY` \| `ALL_ASSOCIATED` | **NEW**, `NOT NULL DEFAULT 'STILL_NEEDED_ONLY'` (FR-017) |

`OrganiserSettingsService.update(...)` gains the corresponding parameters, following the existing
`null`-means-"leave unchanged" convention; `maxGroupMembers`/`minGroupMembers` are validated (FR-011a, FR-011b)
before the database `CHECK` constraints would ever need to fire, mirroring `maxRegistrations`'s existing
pre-check in the same method.

Read on every join attempt, compliance evaluation, and Skills-column render, with no in-memory caching —
identical freshness guarantee to 003/004's existing toggles (FR-018, FR-020c).

## New Entities

### Custom Field Diversity Requirement (`compliance_diversity_requirements`, new table — FR-011, FR-011d, FR-012a)

| Field | Type | Rules |
|---|---|---|
| `id` | uuid | PK, `DEFAULT uuidv7()` |
| `custom_field_definition_id` | uuid | FK → `custom_field_definitions (id)`; unique (at most one requirement per field — research.md §3) |
| `minimum_distinct_values` | integer | `CHECK (minimum_distinct_values >= 2)` (FR-011d) |
| `created_at`, `updated_at` | timestamptz | `NOT NULL DEFAULT now()` |

Owned by `ComplianceDiversityRequirementRepository extends ReactiveCrudRepository<ComplianceDiversityRequirement,
UUID>` (a real one-to-many collection with its own payload, not a `DatabaseClient`-backed pure-association
table — research.md §3) in a new `compliance` domain package. `ComplianceService` exposes
`findAll()`/`addRequirement(UUID customFieldDefinitionId, int minimumDistinctValues)`/`removeRequirement(UUID
id)`, each Organiser-only (FR-019), `addRequirement` rejecting a minimum below 2 (FR-011d) and an unknown or
already-configured `customFieldDefinitionId` with a friendly `ComplianceConflictException` before the database
constraints would need to.

**Removal-blocking**: `CustomFieldService.deleteDefinition` gains a third `countReferencing(...)` term against
this table (research.md §8), so deleting a Custom Field Definition currently referenced by a diversity
requirement is blocked the same way a Participant-value reference already blocks it today.

### Compliance Status *(computed, not persisted — research.md §5)*

```java
public enum ComplianceStatus { COMPLIANT, NOT_COMPLIANT, COMPLIANT_OVERRIDE }
```

Produced by `ComplianceService.evaluate(Group group, List<UUID> memberParticipantIds)` (FR-012, FR-012a,
FR-014): `COMPLIANT_OVERRIDE` if `group.complianceOverride` is set; otherwise `COMPLIANT` iff every configured
rule holds — `memberParticipantIds.size() <= maxGroupMembers` (research.md §1's inclusive reading), `size() >=
minGroupMembers` when set, and, for every `ComplianceDiversityRequirement`, at least `minimumDistinctValues`
distinct non-blank `custom_field_values.free_text_value`/selected-option values recorded across
`memberParticipantIds` for that requirement's field — else `NOT_COMPLIANT`. **"No Group Yet"** (FR-014's fourth
display state) is not a value this method ever returns — callers branch on it themselves when
`GroupService.findActiveGroupForTopic(topicId)` completes empty, since there is no `Group` to pass in at all.

### Skill Display Mode *(enum, no new table — see Organiser Settings above)*

```java
public enum SkillDisplayMode { STILL_NEEDED_ONLY, ALL_ASSOCIATED }
```

Resolved at read time by `TopicDiscoveryService` (research.md §7): given a Topic's full needed-Skill set and
the union of Skills held by its current Group's active members (empty set if no Group yet — Edge Cases),
`STILL_NEEDED_ONLY` subtracts the covered set; `ALL_ASSOCIATED` does not.

## New value types (application-level only, no new tables)

- **`TopicDiscoveryService.OpenTopicRow(Topic topic, int memberCount, List<Skill> viewerOfferedSkills, boolean
  pinned)`** — one Home Page row (FR-004): `viewerOfferedSkills` is the display-mode-filtered needed-Skill set
  intersected with the viewing user's own `participant_skills` (empty list, never an error, for a viewer with
  no Participant record or no matching Skills — FR-004 Acceptance Scenario 5). **`pinned`** is **NEW** (FR-033,
  research.md §11): `true` for a row shown only because it is the viewer's own Topic (would otherwise be
  excluded as full or Pending, or would have fallen outside the fullness ranking); templates use it only to
  group the pinned rows above the rest, not to change any other column's content.
- **`TopicDiscoveryService.OverviewRow(Topic topic, String authorDisplayName, int memberCount, List<Skill>
  neededSkills, Optional<ComplianceStatus> complianceStatus, boolean pinned)`** — one Topic Overview row
  (FR-006); an empty `complianceStatus` now renders as a blank Compliance cell (FR-014a, research.md §12,
  supersedes the original "No Group Yet" text). **`pinned`** is **NEW** (FR-034, research.md §11), same meaning
  as `OpenTopicRow.pinned`.
- **`TopicDiscoveryService.TopicDetailView(Topic topic, List<Skill> neededSkills, int memberCount,
  Optional<ComplianceStatus> complianceStatus, List<ParticipantService.ParticipantViewerDetail> members, boolean
  isAuthor, boolean isMember)`** — **NEW** (FR-030–FR-032, FR-037, research.md §13–§14): the Topic Details
  view's full read model, rendered as the "Topic Info" table (`topic`, `neededSkills`, `memberCount`,
  `complianceStatus` — one row each, FR-030) plus the "Joined Participants" table (`members`, FR-031). `members`
  is one `ParticipantViewerDetail` per currently-joined Participant (research.md §10 — each already carries only
  the Custom Field values and Skills this specific viewer may see, via the *existing*
  `ParticipantService.findDetailForViewer`, reused verbatim rather than re-derived). `isAuthor` drives whether
  the template shows the existing edit-form link (Story 9 Acceptance Scenario 5). **`isMember`** is **NEW**
  (FR-037, research.md §14): `true` when the viewer's own Participant record currently belongs to this Topic's
  Group (set from the same `GroupService.findActiveGroupForParticipant` lookup `TopicJoinService.leave` uses),
  driving whether the template shows the Leave form (Story 11) — `false` for a viewer with no Participant
  record, no active Group, or an active Group for a *different* Topic. Nothing in this record, and
  nothing `topics/detail.html` renders from it, names "Group" (FR-036) — `memberCount`/`complianceStatus` are
  the same Topic-scoped figures `OverviewRow` already exposes, not a `Group` reference.
- **`TopicJoinService`** *(new orchestrating service, `topic` package)* — composes the eligibility gate
  (`OrganiserSettings.topicJoiningEnabled`, FR-020b; the requester's `ParticipantStatus == ACTIVE`, FR-007b;
  the Topic's `approvalStatus == APPROVED`) in front of the race-safe `GroupService.join(...)` core
  (data-model.md "Group" above), translating each rejection reason into a distinct
  `TopicJoinConflictException` message the controller can show inline (FR-026). **Gains `leave(UUID topicId,
  UUID requesterUserId)`** (Story 11, FR-037–FR-037e, research.md §14): a shorter gate — the requester must
  have a Participant record with a currently-active Group whose `topicId` matches (FR-037b; no
  `topicJoiningEnabled` or `ParticipantStatus` check, FR-037e) — in front of the equally race-safe
  `GroupService.leave(...)` core.

## Modified read-model methods (`TopicDiscoveryService` — research.md §11, §13)

| Method | Change |
|---|---|
| `findOpenTopicsForHomePage(UUID viewerParticipantIdOrNull, int limit)` | **gains** a leading `UUID viewerUserId` parameter (FR-033): needed to identify the viewer's *authored* Topics (`topic.getCreatedByUserId()`), a different id than `viewerParticipantIdOrNull` (a Participant id, used only for the Skills-offered intersection, FR-004 — unchanged). `HomeController` passes both. |
| `findTopicOverview(UUID viewerUserId, boolean viewerIsOrganiser)` | **unchanged signature** — `viewerUserId` already identifies authorship for FR-034's pinning; only the method body changes (research.md §11). |
| `findTopicDetail(UUID topicId, UUID viewerUserId, boolean viewerIsOrganiser)` | **NEW** (FR-030–FR-032, FR-037): `Mono<TopicDetailView>`, empty (→ 404) for an unknown or Pending-and-invisible Topic, reusing the same package-private static `TopicService.isVisibleTo(...)` check `findTopicOverview` already calls (no new dependency for this part — `TopicDiscoveryService` and `TopicService` are already in the same `topic` package). Requires `TopicDiscoveryService` to gain `ParticipantService` as one new constructor dependency, for the per-member `findDetailForViewer` calls (research.md §10), and `GroupService.findActiveGroupForParticipant` (already a dependency, via `GroupService`) for the new `isMember` field (research.md §14). |

## Schema additions (illustrative DDL — final statements land in `schema.sql`)

```sql
-- Organiser Settings: Compliance Ruleset (max/min), Topic-joining toggle, Skill Display Mode (research.md §3)
ALTER TABLE organiser_settings ADD COLUMN IF NOT EXISTS max_group_members integer NOT NULL DEFAULT 5;
ALTER TABLE organiser_settings ADD COLUMN IF NOT EXISTS min_group_members integer;
ALTER TABLE organiser_settings ADD COLUMN IF NOT EXISTS topic_joining_enabled boolean NOT NULL DEFAULT true;
ALTER TABLE organiser_settings
    ADD COLUMN IF NOT EXISTS skill_display_mode text NOT NULL DEFAULT 'STILL_NEEDED_ONLY';

ALTER TABLE organiser_settings DROP CONSTRAINT IF EXISTS organiser_settings_max_group_members_check;
ALTER TABLE organiser_settings
    ADD CONSTRAINT organiser_settings_max_group_members_check CHECK (max_group_members >= 1);

ALTER TABLE organiser_settings DROP CONSTRAINT IF EXISTS organiser_settings_min_le_max_group_members_check;
ALTER TABLE organiser_settings
    ADD CONSTRAINT organiser_settings_min_le_max_group_members_check
    CHECK (min_group_members IS NULL OR min_group_members <= max_group_members);

-- Group: Organiser compliance override (research.md §4)
ALTER TABLE groups ADD COLUMN IF NOT EXISTS compliance_override boolean NOT NULL DEFAULT false;

-- Custom Field Diversity Requirements: one row per configured requirement (research.md §3)
CREATE TABLE IF NOT EXISTS compliance_diversity_requirements (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    custom_field_definition_id uuid NOT NULL REFERENCES custom_field_definitions (id),
    minimum_distinct_values integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE compliance_diversity_requirements
    DROP CONSTRAINT IF EXISTS compliance_diversity_requirements_minimum_check;
ALTER TABLE compliance_diversity_requirements
    ADD CONSTRAINT compliance_diversity_requirements_minimum_check CHECK (minimum_distinct_values >= 2);

CREATE UNIQUE INDEX IF NOT EXISTS compliance_diversity_requirements_field_key
    ON compliance_diversity_requirements (custom_field_definition_id);
```

`ALTER TABLE ... ADD CONSTRAINT` has no `IF NOT EXISTS` form in PostgreSQL; each `CHECK` addition above follows
004's established `DROP CONSTRAINT IF EXISTS` + plain `ADD CONSTRAINT` idempotent-rerun idiom (schema.sql,
already used for `organiser_settings_max_registrations_check`).

## Entity relationship summary

```text
Topic 1───0..1 Group (active)                         (002, unchanged — one active Group per Topic)
Group 1───* GroupMember (group_members, active flag)   (002, unchanged — one active Group per Participant)
Topic *───* Skill (topic_skills)                       (002, unchanged; now also written by propose/updateAsAuthor)
Participant *───* Skill (participant_skills)           (002, unchanged — read for viewer-offered/coverage sets)
Group.complianceOverride                                (NEW — short-circuits ComplianceService.evaluate)
OrganiserSettings (singleton, extended)                — max/min Group Members, Topic-joining toggle,
                                                          Skill Display Mode; read by GroupService.join,
                                                          ComplianceService, TopicDiscoveryService, TopicJoinService
CustomFieldDefinition 1───0..1 ComplianceDiversityRequirement (NEW table; unique per field)
ComplianceDiversityRequirement ──reads──> custom_field_values / custom_field_value_options
                                          (of each of a Group's current members, at evaluation time)
```

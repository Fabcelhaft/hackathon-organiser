# Phase 1 Data Model: Topic Improvements — Upvoting, Author Visibility & Reference IDs

Continues 002–011's conventions: UUIDv7 PKs via `uuid PRIMARY KEY DEFAULT uuidv7()` for real
entity tables; `created_at`/`updated_at` `timestamptz NOT NULL DEFAULT now()`; composite-key "pure
association" tables manipulated via `DatabaseClient`, never a single-`@Id`
`ReactiveCrudRepository`. `schema.sql` stays the single source of DDL,
`CREATE`/`ALTER ... IF NOT EXISTS` throughout, rerun on every startup
(`spring.sql.init.mode=always`). One new table (`topic_upvotes`) is introduced; the other two
concepts extend existing tables (research.md §4–§7 explain why).

## New Entity: Topic Upvote

One row = one user's currently-active upvote on one Topic (spec Key Entities). A composite-key
pure association table — no independent UUID, `DatabaseClient`-managed like `topic_skills` —
because withdrawing an upvote deletes the row outright (research.md §2); there is no
soft-delete/history requirement the way `group_members.active` has for Group disbandment.

```sql
CREATE TABLE IF NOT EXISTS topic_upvotes (
    topic_id uuid NOT NULL REFERENCES topics (id),
    user_id uuid NOT NULL REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (topic_id, user_id)
);
```

| Field | Type | Rules |
|---|---|---|
| `topic_id` | uuid | FK → `topics.id`, part of PK |
| `user_id` | uuid | FK → `users.id`, part of PK — deliberately **not** exposed to any read model beyond a count (FR-003); never joined into a per-user list anywhere in the UI |
| `created_at` | timestamptz | Informational only (audit-free per research.md §1); not currently rendered anywhere |

**Concurrency**: `TopicUpvoteService.upvote(...)` pre-checks existence and skips the insert if the
caller already has an active upvote; the composite primary key is the structural backstop for the
rare lost race, caught and treated as the same success (research.md §3), mirroring
`GroupService`'s existing pre-check-plus-constraint-backstop convention. `TopicUpvoteService.
withdraw(...)` performs a plain `DELETE ... WHERE topic_id = :t AND user_id = :u`, a no-op (zero
rows affected, not an error) if no upvote existed.

**New service — `TopicUpvoteService`** (package `topic`, alongside `TopicJoinService`):

| Method | Behavior |
|---|---|
| `upvote(UUID topicId, UUID userId)` | `Mono<Void>` — inserts the row; idempotent on a duplicate cast |
| `withdraw(UUID topicId, UUID userId)` | `Mono<Void>` — deletes the row; idempotent if none existed |
| `countsFor(Set<UUID> topicIds)` | `Mono<Map<UUID, Integer>>` — one bulk `GROUP BY topic_id` query, mirroring `TopicService.loadAuthors`'s bulk-map shape; missing keys mean zero (never surfaced as an error) |
| `viewerUpvotedTopicIds(Set<UUID> topicIds, UUID viewerUserId)` | `Mono<Set<UUID>>` — one bulk query for "which of these Topics has this viewer already upvoted", used to render each row's active/inactive control state (FR-004) |

## Modified Entity: Topic (existing `topics` table)

| Field | Type | Rules |
|---|---|---|
| `reference_number` | integer | **NEW**, nullable. `NULL` while `approval_status = 'PENDING'` (FR-009). Set exactly once, inside `TopicService.approve(...)`, via `nextval('topics_reference_number_seq')` (research.md §4); never updated again (FR-011) |

```sql
ALTER TABLE topics ADD COLUMN IF NOT EXISTS reference_number integer;
CREATE UNIQUE INDEX IF NOT EXISTS topics_reference_number_key
    ON topics (reference_number) WHERE reference_number IS NOT NULL;
CREATE SEQUENCE IF NOT EXISTS topics_reference_number_seq;
```

**One-time backfill** (idempotent, safe on every startup — research.md §5 has the full statement
and rationale): every pre-existing `APPROVED` Topic with `reference_number IS NULL` is assigned one,
ordered by its earliest `STATUS_CHANGED → APPROVED` Audit Trail entry (falling back to
`created_at`), after which the sequence is advanced past the highest assigned value.

**Behavioral change, in `TopicService`:**

| Method | Change |
|---|---|
| `approve(UUID topicId, AuditActor actor)` | Now also sets `reference_number = nextval(...)` in the same save, immediately before the existing `STATUS_CHANGED` audit record and `topicApproved` event publish — both of which are unaffected (the reference number itself is not audited or included in the event payload; out of scope for this feature) |

`findById`/`findVisibleTo` are unchanged — `reference_number` rides along as a plain field on the
existing `Topic` object with no new query shape.

## Modified Entity: Organiser Settings (existing `organiser_settings` table)

| Field | Type | Rules |
|---|---|---|
| `topic_upvoting_enabled` | boolean | **NEW**, `NOT NULL DEFAULT true` (research.md §7) — instance-wide gate for FR-006/FR-007/FR-008 |

```sql
ALTER TABLE organiser_settings ADD COLUMN IF NOT EXISTS topic_upvoting_enabled boolean NOT NULL DEFAULT true;
```

**Behavioral additions:**

- `OrganiserSettingsService.update(...)` gains a `Boolean topicUpvotingEnabled` parameter,
  following the exact null-means-unchanged convention `topicJoiningEnabled` already uses.
- `OrganiserSettingsController` reads it via the existing `checkboxValue(form, "topic_upvoting_enabled")`
  helper; `organiser/settings/form.html` gains a checkbox mirroring `topic_joining_enabled`'s markup.
- Every read path that renders upvote controls/counts (`TopicDiscoveryService`'s three read
  models) re-reads `OrganiserSettings.current()` fresh, never caches it — same freshness rule
  `topicJoiningEnabled` already follows (FR-020c precedent), so a toggle flip takes effect on the
  very next page render for every viewer (SC-004).

## Read-Model Extensions (`TopicDiscoveryService`)

The three existing record types each gain the two upvote fields and (where applicable) the
reference number. `TopicDetailView` additionally gains `authorDisplayName` (Story 3, FR-014) — this
one is a genuine new field, not a template-only change: the participant-facing Topic Details view
has no author information today (unlike `OverviewRow`, which has carried `authorDisplayName` since
feature 005). `findTopicDetail` populates it with the same `authorDisplayName(UUID)` helper
`buildOverviewRow` already uses — no new query:

```java
public record OpenTopicRow(
        Topic topic, int memberCount, List<Skill> viewerOfferedSkills, boolean pinned,
        boolean joinable, int upvoteCount, boolean viewerHasUpvoted) {}

public record OverviewRow(
        Topic topic, String authorDisplayName, int memberCount, List<Skill> neededSkills,
        Optional<ComplianceStatus> complianceStatus, boolean pinned, boolean joinable,
        int upvoteCount, boolean viewerHasUpvoted) {}

public record TopicDetailView(
        Topic topic, String authorDisplayName, List<Skill> neededSkills, int memberCount,
        Optional<ComplianceStatus> complianceStatus,
        List<ParticipantService.ParticipantViewerDetail> members, boolean author,
        int upvoteCount, boolean viewerHasUpvoted) {}
```

`topic.getReferenceNumber()` is read directly off the embedded `Topic` by every template — no new
record field needed for it, matching how templates already reach `topic.getName()`.

When `topicUpvotingEnabled` is `false`, `TopicDiscoveryService` skips the bulk
`TopicUpvoteService` calls entirely and populates `upvoteCount = 0`, `viewerHasUpvoted = false` for
every row; the templates additionally check the toggle directly (passed into the model exactly
like `settings.topicJoiningEnabled` already is) to suppress rendering the control and the count,
not merely zero it out (FR-007 — a visible "0 upvotes" is not the same as no upvote UI at all).

## Home Page Ordering (Story 1 Acceptance Scenario 6, FR-005a)

`TopicDiscoveryService.selectHomePageRows`'s fullness comparator (currently
`Comparator.comparingInt(TopicAndGroup::memberCount).reversed()`) becomes, only while the toggle is
on:

```java
Comparator.comparingInt(TopicAndGroup::memberCount).reversed()
        .thenComparing(
                (TopicAndGroup tg) -> upvoteCounts.getOrDefault(tg.topic().getId(), 0),
                Comparator.reverseOrder())
```

applied to the same not-full/Approved candidate list the fullness sort already operates on;
pinning of the viewer's own Topics above it (`byCreatedAt`) is untouched.

## Topic Details Author Display (Story 3, FR-014)

Scoped to the participant-facing single-Topic Details view only (`TopicSelfServiceController`'s
`/{id}` route, `templates/topics/detail.html`) — **not** the Topic Overview (all-Topics list),
which already shows an author column and needs no change here. In
`templates/topics/detail.html`, `detail.authorDisplayName` is rendered as the first row of the
existing "Topic Info" `<table>` (before "Needed Skills"), mirroring the placement Creator already
has as the first `<dl>` row on the Organiser-facing `organiser/topics/detail.html` (a separate,
pre-existing read model that already satisfies this — no change needed there).

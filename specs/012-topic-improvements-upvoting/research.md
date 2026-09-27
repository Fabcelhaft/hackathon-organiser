# Phase 0 Research: Topic Improvements — Upvoting, Author Visibility & Reference IDs

No `[NEEDS CLARIFICATION]` markers remain in the Technical Context (all were resolved by
inspecting the existing codebase and by the `/speckit-clarify` session already folded into
spec.md). This file records the implementation-level decisions that follow from that spec.

## 1. Upvotes must never enter the existing Audit Trail

**Decision**: Casting or withdrawing an upvote records no `AuditEntry` (006). No new
`AuditEventType` value is added for it.

**Rationale**: `AuditEntry.actorUserId` is always populated and every entry is listed, per-actor,
on `organiser/audit/list.html` — visible to any Organiser. Spec FR-003/SC-003 require that no
upvoter's identity is *ever* retrievable "to any user regardless of role," which explicitly
includes Organisers. Recording an audit entry for an upvote would silently violate that guarantee
through a surface this feature doesn't otherwise touch. Every other Topic-affecting self-service
action (Join, Leave, propose, edit) is audited; upvoting is the deliberate, spec-mandated
exception.

**Alternatives considered**: A redacted audit entry (event recorded, actor omitted) was rejected —
it still leaks "someone (n-th distinct upvoter)" timing information and adds a special-cased,
easy-to-regress carve-out to a table whose whole contract today is "every row has an actor."
Simplest and safest is: this table never hears about upvotes at all.

## 2. `topic_upvotes` is a plain composite-key association table, not a soft-deletable one

**Decision**: `topic_upvotes (topic_id, user_id, created_at)`, composite `PRIMARY KEY (topic_id,
user_id)`, manipulated via `DatabaseClient` in a new `TopicUpvoteService` — the same shape as
`topic_skills`/`participant_skills`/`event_destination_event_types`, not the `active`-flag
soft-delete shape `group_members` uses.

**Rationale**: Spec Key Entities is explicit: "withdrawing it removes the record entirely (a later
upvote by the same user on the same Topic is a new, independent cast)" — there is no history to
preserve (unlike `group_members`, where a disbanded Group's membership must remain queryable).
A withdrawn-then-recast upvote has no observable difference from one that was never withdrawn, so
a real `DELETE` is both simpler and correct. No repository is introduced — this project's existing
rule is that a composite-key pure-association table (no independent UUID) is `DatabaseClient`-
managed, never backed by a single-`@Id` `ReactiveCrudRepository`.

**Alternatives considered**: An `active boolean` column mirroring `group_members` was rejected —
it exists there only to satisfy the Group-disbandment history requirement, which upvotes have no
equivalent of, and would add an unused historical trail directly contradicting the "not possible
to see who upvoted" requirement (a never-cleared row is one more place an identity sits at rest).

## 3. One upvote per (Topic, user) is enforced by the primary key itself

**Decision**: `TopicUpvoteService.upvote(...)` pre-checks existence (`SELECT EXISTS(...)`) and
skips the insert if an active upvote is already there; on the rare lost race (two concurrent casts
both pass the pre-check), the composite primary key on `(topic_id, user_id)` turns the second
`INSERT` into a constraint violation, which is caught and treated as the same no-op success —
mirroring `GroupService`'s existing "pre-check for a friendly outcome, constraint as the
structural backstop for the lost-race case" convention (research.md §4 of 005) exactly, except
that here both the pre-checked and the raced-and-caught paths resolve to plain success rather than
a `*ConflictException` — there is nothing to reject, an upvote is idempotent by definition (FR-001).

**Rationale**: Consistent with this codebase's established pattern of preferring a database
constraint over an advisory lock for simple uniqueness, reserving `pg_advisory_xact_lock` for
cases with a genuine multi-statement read-then-write capacity check (Group joining). Casting an
upvote has no capacity check — it is a pure uniqueness guarantee — so the lighter-weight
pre-check-plus-constraint-backstop approach is sufficient and matches precedent.

## 4. Reference numbers: a dedicated sequence, assigned inside `TopicService.approve`

**Decision**: `topics.reference_number integer`, nullable, with a partial unique index
(`WHERE reference_number IS NOT NULL`) plus a new `topics_reference_number_seq` sequence.
`TopicService.approve(...)` calls `nextval('topics_reference_number_seq')` and sets it in the same
save that flips `approval_status` to `APPROVED` — one atomic write, no separate migration step for
newly-approved Topics.

**Rationale**: A dedicated sequence (rather than, say, `COUNT(*) + 1`) is race-free under
PostgreSQL by construction and matches the project's existing preference for native Postgres
primitives (`uuidv7()` for PKs) over application-computed values. Doing the assignment inside the
existing `approve` method — the same method the spec's Story 2 Acceptance Scenario 2 already
targets — needs no new service method and can't be forgotten by a future caller the way a
"remember to also call assignReferenceNumber afterward" convention could be.

**Alternatives considered**: Assigning the number lazily on first display (e.g., "first Topic
someone views after approval gets #1") was rejected — it would make reference numbers depend on
view order, breaking FR-011's "permanent... even if the Topic is later edited" guarantee and the
edge case answer that approval order is what's preserved.

## 5. Backfilling already-Approved Topics: sourced from the Audit Trail, deterministic single pass

**Decision**: The `schema.sql` backfill (idempotent, `WHERE reference_number IS NULL`) orders
existing Approved Topics by the earliest `audit_entries` row where
`subject_type = 'TOPIC' AND event_type = 'STATUS_CHANGED' AND new_value = 'APPROVED'` for that
Topic, falling back to `topics.created_at` for the (expected to be rare — e.g. seeded demo data
inserted pre-approved, per `docs/demo-data.sql`) case where no such audit entry exists. It assigns
each ordered row a plain `ROW_NUMBER()`-derived integer directly (not `nextval()` inside the
`UPDATE`, since PostgreSQL does not guarantee `UPDATE ... FROM` visits rows in a CTE's `ORDER BY`
sequence), then advances `topics_reference_number_seq` to `MAX(reference_number) + 1` in a
follow-up `setval(...)` so the very next live `approve()` call continues cleanly from there.

```sql
WITH ordered AS (
    SELECT t.id,
           ROW_NUMBER() OVER (
               ORDER BY COALESCE(
                   (SELECT MIN(a.occurred_at) FROM audit_entries a
                    WHERE a.subject_type = 'TOPIC' AND a.subject_id = t.id
                      AND a.event_type = 'STATUS_CHANGED' AND a.new_value = 'APPROVED'),
                   t.created_at
               )
           ) AS rn
    FROM topics t
    WHERE t.approval_status = 'APPROVED' AND t.reference_number IS NULL
)
UPDATE topics t SET reference_number = ordered.rn
FROM ordered WHERE t.id = ordered.id;

SELECT setval('topics_reference_number_seq', COALESCE((SELECT MAX(reference_number) FROM topics), 0) + 1, false);
```

**Rationale**: The Audit Trail (006) already timestamps every approval, so "ordered by original
approval time" (the accepted edge-case answer in spec.md) is fully recoverable without inventing a
new historical field. The `WHERE reference_number IS NULL` guard makes the whole block a no-op on
every startup after the first, matching this file's existing convention (e.g. the 008
`is_homepage`→`context` migration comment) for a genuine one-time backfill that is safe to leave
in `schema.sql` forever. The `setval` call is cheap and safe to re-run unconditionally.

**Alternatives considered**: Ordering by `created_at` alone (ignoring the Audit Trail) was
rejected — a Topic proposed early but approved late would then jump the queue ahead of one
proposed later but approved sooner, contradicting the clarified "approved earlier receive lower
numbers" rule.

## 6. Home Page sort tiebreak: extend the existing `Comparator`, gated by the feature toggle

**Decision**: In `TopicDiscoveryService`, the existing
`Comparator.comparingInt(TopicAndGroup::memberCount).reversed()` used for the fullness-sorted list
gains `.thenComparing(tg -> upvoteCounts.getOrDefault(tg.topic().getId(), 0), Comparator.reverseOrder())`
— but only when `organiserSettings.isTopicUpvotingEnabled()`; when the toggle is off, the
comparator is used unchanged (no upvote lookup even performed), so a disabled feature has zero
runtime cost and zero influence on ordering, matching FR-005a and the corresponding edge case.

**Rationale**: This is a one-line extension of a single, already-shared comparator rather than a
parallel sorting path — the same `Comparator` also governs the Home Page's "own Topics pinned
above the rest" sub-ordering (`byCreatedAt` there is untouched; the tiebreak only applies within
the fullness-sorted list feeding the non-pinned slots, per spec.md's Story 1 Acceptance Scenario
6, which is scoped to "not-full Topics").

## 7. Admin toggle default: enabled

**Decision**: `organiser_settings.topic_upvoting_enabled boolean NOT NULL DEFAULT true`.

**Rationale**: Unlike `skill_visibility_enabled`/`teams_links_enabled` (both default `false`
because they expose additional personal information about Participants — an explicit
opt-in-to-more-disclosure posture), upvoting discloses nothing about any individual (FR-003) and
is additive UI value with no privacy trade-off, closer in kind to `topic_joining_enabled` (default
`true`) than to the disclosure-widening toggles. Defaulting to enabled means existing installs get
the feature visibly on upgrade, matching the spirit of Story 1 being the feature's primary
deliverable rather than a niche opt-in.

**Alternatives considered**: Defaulting to `false` (organiser must opt in) was considered for
symmetry with `skill_visibility_enabled`, but rejected once the actual reason for those defaults
(information disclosure risk) was found not to apply here.

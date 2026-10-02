# Phase 1 Data Model: Cleaner Topic List UI

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Date**: 2026-10-01

## Database schema

**No change.** `schema.sql` is untouched by this feature, and therefore `docs/demo-data.sql`
needs no update either (the repository rule in `CLAUDE.md` is triggered by table, column or
enum-value changes, none of which occur here).

The feature relies on one existing structural guarantee rather than adding any:

```
topic_upvotes (
    topic_id  uuid NOT NULL REFERENCES topics (id),
    user_id   uuid NOT NULL REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (topic_id, user_id)      -- makes casting idempotent (FR-006d)
)
```

That composite primary key, plus `TopicUpvoteService.withdraw`'s no-op-when-absent behaviour, is
what satisfies FR-006d's "at most one upvote per participant per Topic" without any new
coordination — including under the rapid, overlapping activation that optimistic updating makes
more likely.

## Read-model changes

Only one type changes shape.

### `TopicDiscoveryService.OpenTopicRow` (the Dashboard row)

| Field | Before | After | Why |
|---|---|---|---|
| `topic` | `Topic` | unchanged | |
| `memberCount` | `int` | unchanged | |
| `viewerOfferedSkills` | `List<Skill>` | **removed** | FR-015a — the column is gone, so the value has no reader |
| `pinned` | `boolean` | unchanged | |
| `joinable` | `boolean` | unchanged | |
| `upvoteCount` | `int` | unchanged | |
| `viewerHasUpvoted` | `boolean` | unchanged | |

### `TopicDiscoveryService.OverviewRow` (the Topics overview row)

**Unchanged.** Its `neededSkills` is a property of the Topic, not of the viewer, and FR-016 keeps
that column. Do not confuse the two: removing the Dashboard's viewer-intersected skills must not
touch the overview's needed skills.

### Method signature

```
findOpenTopicsForHomePage(UUID viewerUserId, UUID viewerParticipantIdOrNull, int limit)
    → findOpenTopicsForHomePage(UUID viewerUserId, int limit)
```

`viewerParticipantIdOrNull` existed solely to feed the viewer-skill intersection. With that gone
the parameter is dead; leaving it in place would be an invitation to reintroduce the column.
`HomeController` is the only caller.

### Private helper removed

`viewerOfferedSkillIds(UUID, List<UUID>)` is called from `findOpenTopicsForHomePage` and nowhere
else, so it is deleted rather than left orphaned. `displayedNeededSkillIds(...)` and
`loadSkills(List<UUID>)` are **kept** — the overview and detail paths both still use them. See
[research.md](./research.md) §6 for the call-site audit that FR-015b requires.

## Validation rules

No new validation. The feature changes presentation and transport, not what is valid:

- Upvote eligibility is unchanged: any authenticated user whose request passes the feature toggle
  and the Topic-visibility gate, exactly as `TopicUpvoteController.gate(...)` enforces today.
- The `redirect` form value stays validated against its existing allow-list (`/`,
  `/topics/overview`, `/topics/{uuid}`). The new fragment branch does not read it at all, so it
  cannot become a new open-redirect surface.

## State transitions

The viewer's own vote state per Topic, as the compact control presents it:

```
                    activate (optimistic, immediate)
   unvoted  ───────────────────────────────────────────►  voted (unconfirmed)
      ▲                                                        │
      │                                                        │ server confirms
      │  server rejects / request fails                        ▼
      └──────────────────────  revert + role="alert" notice   voted (confirmed)
                                                                │
                                                                │ activate again
                                                                ▼
                                                        unvoted (unconfirmed) → …
```

Three rules govern this, all from the spec:

1. The *unconfirmed* states are visual only. No announcement is made while in them (FR-008d).
2. A reversal out of an unconfirmed state is itself announced (FR-008d) and shown beside the
   control (FR-006c1).
3. Where activations overlap, the display settles on the outcome of the **last** activation, not
   whichever response returns last (FR-006d).

With scripting unavailable the unconfirmed states do not exist at all: the form posts, the server
responds, the page re-renders in a confirmed state (FR-006b).

# Contract: Topic Reference Number (Story 2 — FR-009–FR-013)

No new HTTP route — this contract governs when the field is populated and where it is displayed,
not an interface a client calls directly.

## Assignment point: `TopicService.approve(UUID topicId, AuditActor actor)`

**Existing method, extended** (data-model.md). The only place in the codebase that transitions a
Topic from `PENDING` to `APPROVED`. On that transition (and only on that transition — the method's
existing "already Approved → no-op" early return is unchanged and does **not** re-assign or touch
`reference_number`):

1. `reference_number = nextval('topics_reference_number_seq')` is set on the same `Topic` object.
2. The existing save, `STATUS_CHANGED` audit record, and `topicApproved` event publish proceed
   unchanged — the reference number is not itself part of the audit old/new values or the event
   payload (no consumer of either needs it; the UI reads it straight off the Topic).

A still-`PENDING` Topic's `reference_number` is always `NULL` (FR-009) — nothing else in the
codebase ever writes to this column.

## One-time backfill (startup migration, `schema.sql`)

Every Topic that was already `APPROVED` before this feature's schema change is assigned a
`reference_number` automatically on the next application startup, no Organiser action required
(FR-013, SC-006). Full statement and ordering rationale: research.md §5, data-model.md. Idempotent
— a no-op on every startup after the first, following this file's established convention for
one-time backfills (e.g. the 008 `content_pages.context` migration).

## Uniqueness (FR-010, FR-011)

Enforced structurally by the partial unique index
`topics_reference_number_key ON topics (reference_number) WHERE reference_number IS NOT NULL`
(data-model.md) — not just application-level convention. Two concurrent `approve(...)` calls each
draw a distinct value from the sequence; the index is a second, structural guarantee against any
future code path accidentally assigning the same number twice.

## Display contract (FR-012)

Wherever a Topic with a non-null `reference_number` is shown — Home Page, Topic Overview, Topic
Details — the template renders it (e.g. as `#42`) alongside the Topic's other details. A `NULL`
`reference_number` (a still-Pending Topic, visible only to its author/Organisers per the existing
Pending-visibility rule) renders nothing for this field — no placeholder text, mirroring how a
`NULL` Compliance status already renders as a genuinely blank cell (005 precedent) rather than a
label like "N/A".

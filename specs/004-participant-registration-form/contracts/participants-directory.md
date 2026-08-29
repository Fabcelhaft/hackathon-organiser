# Contract: Participants Directory & Detail View

New package `participants` (plural), routes `GET /participants` and `GET /participants/{id}`. Access is
gated by the *configurable* audience setting (`organiser_settings.participants_directory_audience`), not a
fixed `SecurityConfig` role rule — see research.md §6 for why, and
`ParticipantsDirectoryAccessPolicy` for the single-source-of-truth check shared with the nav model advice.

## Navigation item (FR-025)

`fragments/layout.html` renders a "Participants" nav link iff `showParticipantsMenuItem` is `true` — a model
attribute `CurrentUserModelAdvice` computes by calling
`ParticipantsDirectoryAccessPolicy.isInAudience(settings, isOrganiser, hasParticipantRecord)` for the current
request's user. Absent entirely (not merely disabled) for a user outside the configured audience (FR-025's
"no Participants menu item is shown").

## GET /participants

- **200** — the requesting user is within the configured audience
  (`ORGANISERS_ONLY` → organisers; `ORGANISERS_AND_PARTICIPANTS` → organisers or users with any Participant
  record, any status; `ALL_AUTHENTICATED` → anyone logged in). Renders `participants/list.html`: one row per
  **registered** (`ACTIVE`, FR-027, spec Assumptions) Participant, ordered alphabetically ascending by
  `users.display_name` (FR-027a), one column per `CustomFieldDefinition` with `overview = true` (FR-027,
  regardless of its `public` flag — Overview columns follow FR-017's own-row/Organiser-or-nothing rule, not
  the plain `public` rule, since the viewer here might be a non-owning Participant). A cell for a Participant
  who never filled in that field renders a clear "—"/"Not provided" empty indicator, never a blank or error
  (FR-031). **No** Skills column, ever (FR-027).
- **403 Forbidden** — outside the configured audience, including via a direct link when the menu item itself
  would be hidden (FR-026, Edge Cases).

## GET /participants/{id}

- **200, "self" mode** — `{id}` is the requester's own Participant id: identical rendering to
  `contracts/registration-and-self-edit.md`'s `GET /profile` (in fact `GET /profile` is implemented as a thin
  redirect/delegate to this same handler with the caller's own id, so there is exactly one detail-rendering
  code path — no duplicated visibility logic to drift). Bypasses the audience check entirely: a Participant
  can always view their own detail page regardless of the configured directory audience (per the
  clarification recorded in spec.md).
- **200, "organiser" mode** — requester `isOrganiser = true`: every Custom Field value and Skill selection
  shown regardless of `public`/`overview`/skill-visibility flags (FR-030).
- **200, "other viewer" mode** — requester is within the configured audience but viewing someone else's page:
  only `public = true` Custom Field values are shown (an Overview-only, non-Public field is omitted here,
  per FR-017); Skills are shown only if `organiserSettings.skillVisibilityEnabled` is currently `true`
  (FR-019, FR-018); every omitted field is simply absent from the rendered page, never shown as blank/locked
  (FR-006, User Story 5's own "never fields left private" wording).
- **404 Not Found** — `{id}` does not correspond to any Participant record (including a Not-`ACTIVE` one that
  an Organiser would see via the separate 002 organiser participant-management views, not this route, per
  spec Assumptions — this route only serves `ACTIVE` records for non-organisers; an Organiser viewing a
  non-`ACTIVE` record's detail continues to use the existing `organiser/participants/detail.html` view from
  002, unchanged by this feature).
- **403 Forbidden** — requester is outside the configured audience and `{id}` is not their own (same
  direct-URL-denial guarantee as the table itself, FR-026).

## Read model composition

Both routes share one `ParticipantService` read method,
`findDetailForViewer(UUID participantId, UUID viewerUserId, boolean viewerIsOrganiser)`
(data-model.md), which returns each Custom Field value/Skill selection already paired with a computed
"visible to this viewer" boolean and a "visible to others generally" boolean — the latter drives FR-020's
"visible to others / private" label shown only in self mode; the former is what the template actually uses to
decide whether to render the row at all in non-self, non-organiser mode.

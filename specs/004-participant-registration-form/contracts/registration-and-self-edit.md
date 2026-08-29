# Contract: Registration Form, Self-Edit, and Read-Only Own Profile

New package `participants` (plural), outside `/organiser/**` — plain `.anyExchange().authenticated()` per
`SecurityConfig`, same as `home`/`info`/`topics`. Supersedes 003's `POST /register` immediate-registration
behavior (FR-001).

## GET / (home) — changed link, not a changed route (FR-001)

**Modified** (`HomeController`, unchanged path). The "Register" action becomes a plain link to `GET
/register` instead of a `POST /register` form button — no click-triggers-creation behavior remains. It is
shown/hidden by the same `canRegister` computation 003 already has (self-registration enabled AND no
`ACTIVE` Participant record), now additionally suppressed entirely when the current user's Participant
status is `NOT_PARTICIPATED` (FR-006a) — in which case a static message is shown instead: "Your participation
status was set by an Organiser. Only an Organiser can change it." The existing `POST /revoke` route/behavior
(FR-001a) is otherwise unchanged, gaining only the same `NOT_PARTICIPATED` suppression.

## GET /register

**New.** Shows the registration/reactivation form.

- **200** — every enabled `CustomFieldDefinition` (`CustomFieldService.registrationFields()`, i.e. all
  non-`COUNTRY` rows plus the `COUNTRY` row only if `enabled = true`) rendered per
  `custom-fields-and-country.md`'s rendering contract, plus the full Skill catalog as a multi-select — all
  presented as one flat set of ordinary fields (FR-002a), required ones visually marked (FR-002). If the
  caller has an existing `REVOKED` Participant record, every field is pre-filled with that record's stored
  values (FR-006).
- **200, form replaced by a capacity message** — `organiserSettings.maxRegistrations` is set and the current
  `ACTIVE` count has already reached it: "Maximum registrations reached" is shown in place of the form
  (FR-010), before the user invests time filling anything in.
- **303 → `/?flash=...`** — the caller's Participant status is `NOT_PARTICIPATED` (FR-006a) or
  `selfRegistrationEnabled` is currently `false`: redirected home with an explanatory flash rather than shown
  an empty/broken form.

## POST /register

**New.** Submits the form built above.

- **303 → `/?flash=Registration+successful.`** — all required fields present and valid, capacity not
  exceeded, status not `NOT_PARTICIPATED`, self-registration enabled: `ParticipantService.submitRegistration`
  (research.md §4's transactional, advisory-lock-guarded path) creates (or reactivates) the `ACTIVE` record
  with exactly the submitted Custom Field values and Skill selections (FR-005, FR-033).
- **200, form re-rendered with field-level errors** — one or more required fields missing, or a submitted
  value doesn't match its field's type (e.g. two options for a `SINGLE_SELECT`, a non-ISO code for `COUNTRY`):
  no Participant record is created or changed (FR-003, FR-034 messaging pattern), submitted values are
  preserved so the user doesn't retype everything.
- **200, form re-rendered with the capacity message** (FR-035) — `RegistrationCapacityReachedException`: the
  count reached the maximum in the moment between page load and submission (Edge Cases: race). Distinct
  message from field-validation errors, matching `GET /register`'s own capacity message text.
- **303 → `/?flash=...`** — `NOT_PARTICIPATED` or self-registration disabled at submission time, even though
  the page displayed the form when it loaded (FR-006a, FR-009: server-side re-check regardless of page
  state).
- Submit control disabled client-side for the duration of the request (FR-036); server-side idempotency is
  provided by `submitRegistration`'s existing "already-`ACTIVE`-is-a-no-op" branch (003's behavior, retained)
  in case a double-click still reaches the server twice.

## GET /profile

**New.** The caller's own profile, read-only, always available regardless of `selfEditEnabled` (FR-023) or
`NOT_PARTICIPATED` status (a dead-end status still permits viewing one's own stored values) — 404 only if the
caller has no Participant record at all (nothing to view yet; the page links to `GET /register` instead in
that case, handled by `HomeController`, not this route).

- **200** — renders `participants/detail.html` in "self" mode: every Custom Field value and Skill selection
  shown regardless of `public`/`overview` flags (FR-029), each one labeled "visible to others" or "private"
  per its actual `public`/skill-visibility configuration, via text/icon not color alone (FR-020, FR-041).
  An "Edit" action/link to `GET /profile/edit` is shown only if `selfEditEnabled` is currently `true`
  (FR-023).

## GET /profile/edit

**New.**

- **200** — pre-filled with the caller's current values (FR-022's "pre-filled" requirement), same field
  rendering as `GET /register`.
- **303 → `/profile`** — `selfEditEnabled` is currently `false`: no edit form is served even via direct
  navigation (consistent with FR-023's "no edit action... presented"; a friendly redirect rather than a bare
  403, matching the capacity-message UX precedent in `GET /register`).
- **303 → `/profile`** — the caller's status is `NOT_PARTICIPATED` (self-edit is one of the self-service dead
  ends per FR-006a's spirit — an Organiser-only status has no self-service surface at all) or the caller has
  no Participant record.

## POST /profile/edit

**New.**

- **303 → `/profile?flash=Profile+updated.`** — valid submission: `ParticipantService.submitSelfEdit`
  persists the new values (FR-022, FR-034), reflected immediately on the next `GET /profile` (SC-005).
- **200, form re-rendered with field-level errors** — same validation as registration (FR-003, FR-022,
  FR-034): a missing required field or a type-mismatched value is rejected, identifying which field(s) and
  why.
- **303 → `/profile?flash=...`** — `selfEditEnabled` became `false`, or status became `NOT_PARTICIPATED`,
  between page load and submission: rejected server-side regardless of what the page displayed at load
  (FR-024, Edge Cases).

## Shared field-rendering fragment

`fragments/profile-fields-form.html` is included by both `participants/register.html` and
`participants/edit.html` so the Custom Field/Skill/Country control markup — and its required/optional
marking (FR-002), label association (FR-039), and keyboard operability (FR-038) — is defined exactly once and
never drifts between the two forms.

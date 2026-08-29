# Contract: Organiser Settings Extensions

Extends 003's `organiser/settings/OrganiserSettingsController` (`GET`/`POST /organiser/settings`,
`ROLE_ORGANISER` only). The existing three toggles (self-registration, self-revocation, topic-approval) are
unchanged; this feature adds four fields to the same single form.

## GET /organiser/settings

**Extended.** Renders four additional controls alongside the existing three toggles:

| Field | Control | Current value source |
|---|---|---|
| Maximum registrations | Number input, empty = unlimited | `settings.maxRegistrations` (nullable) |
| Allow participants to edit their own profile | Checkbox | `settings.selfEditEnabled` |
| Show participants' skills to other users | Checkbox | `settings.skillVisibilityEnabled` |
| Participants directory visible to | Radio group: Organisers only / Organisers and Participants / All authenticated users | `settings.participantsDirectoryAudience` |

## POST /organiser/settings

**Extended.** Same submission convention as the existing three checkboxes (hidden `"false"` + checkbox
`"true"` pair per field; a field entirely absent from the form means "leave unchanged", per
`OrganiserSettingsService.update`'s existing `null` convention).

- **Maximum registrations**: blank submitted value → `maxRegistrations = null` (no limit). A non-blank value
  is parsed as an integer; `< 1` or non-numeric is rejected — the form is re-rendered with the submitted
  values and an inline error identifying the field (FR-007, FR-043), and **no** setting is changed (all four
  new fields plus the existing three are validated/applied atomically — a rejected `maxRegistrations` value
  must not silently apply the other three fields' changes). No other combination of the four new fields has a
  validation rule (booleans and the closed-set audience radio group cannot be invalid).
- On success: redirect to `GET /organiser/settings?flash=Settings+updated.` (FR-032/FR-033 pattern, unchanged
  from 003).

**Effective-immediately guarantee (FR-009, FR-023, FR-024, FR-026 Edge Case)**: every one of these four
settings is read fresh (no caching) on every gated action it controls — `ParticipantService` for the
registration cap and self-edit gate, `ParticipantsDirectoryAccessPolicy` for the directory audience and
`ParticipantService`'s read models for skill visibility — so a change here takes effect on the very next
request, exactly like 003's existing three toggles.

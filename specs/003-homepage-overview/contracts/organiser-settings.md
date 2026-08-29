# Contract: Organiser Settings (Story 2)

Covers FR-005, FR-005a, FR-008, FR-008a, FR-023. Server-rendered Thymeleaf views. All routes require
`ROLE_ORGANISER` (`/organiser/**`, per `SecurityConfig`).

| Method | Path | Purpose | Success | Failure |
|---|---|---|---|---|
| `GET` | `/organiser/settings` | View the three toggles (self-registration, self-revocation, topic-approval), each with its current on/off state and a plain-language effect sentence (FR-005a) | 200, `organiser/settings/form` view | — |
| `POST` | `/organiser/settings` | Update any combination of the three toggles in one submit | Redirect 303 → `/organiser/settings` with a flash confirmation | 200 form re-rendered with a validation error only if the submission is malformed (e.g. missing expected fields) — toggles themselves have no invalid combination |

## Behavioral notes

- A single global row (`organiser_settings`, singleton) backs all three toggles — no per-event/per-tenant
  scoping (data-model.md).
- Every other route in the system that checks these flags (`POST /register`, `POST /revoke`, topic-propose)
  reads the current row fresh on each request — a toggle here takes effect immediately, no restart, no cache
  to invalidate (FR-023, SC-003).
- FR-008/FR-008a (the top-nav Organiser link, icon+text, visible only to `ROLE_ORGANISER`) is not a route of
  its own — it's rendered by the shared layout fragment via the `isOrganiser` model attribute every
  controller receives (research.md §7), and links here to `/organiser/settings` among the other existing
  `/organiser/*` sections.

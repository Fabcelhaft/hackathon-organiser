# Contract: User Management (Story 1)

Covers FR-002, FR-003, FR-004, FR-005, FR-018, FR-022. Server-rendered Thymeleaf views, no JSON API.

**Access**: All routes require an authenticated session; every route below additionally requires
`ROLE_ORGANISER` (FR-022) — an unauthenticated or non-Organiser request is redirected/denied per the global
security rule in [../research.md](../research.md) §2, not repeated per-route below.

| Method | Path | Purpose | Success | Failure |
|---|---|---|---|---|
| `GET` | `/organiser/users` | List all known Users, showing `display_name`, `email`, and current Organiser flag | 200, list view | — |
| `GET` | `/organiser/users/{id}` | View one User's detail | 200, detail view | 404 if `id` unknown |
| `POST` | `/organiser/users/{id}/organiser` | Toggle the Organiser privilege for that User (form field `organiser=true\|false`) (FR-004) | Redirect 303 → `/organiser/users/{id}`, flag updated | 404 if `id` unknown |

## Behavioral notes

- User records are never created through this UI — they are auto-provisioned on first login (FR-002); this
  contract only covers listing/viewing/toggling.
- Toggling a User's own Organiser flag off is allowed by this contract (the spec does not carve out a
  self-protection exception); the effect is that the *next* privilege check for that session denies access
  (edge case in spec: "Subsequent requests MUST be denied even if their current session was already in
  progress").
- Acceptance Scenario 2/3 (spec Story 1): granting/revoking via `POST .../organiser` is reflected "on the next
  access check" — i.e. the next request evaluated against `ROLE_ORGANISER`, per the reactive OIDC user-service
  re-derivation described in [../research.md](../research.md) §2.

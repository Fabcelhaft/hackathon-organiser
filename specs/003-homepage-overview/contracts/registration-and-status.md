# Contract: Homepage, Registration Status & Self-Service Registration/Revocation (Story 1)

Covers FR-001, FR-001a, FR-002–FR-004a, FR-006, FR-007, FR-007a. Server-rendered Thymeleaf views. All routes
require plain authentication (`.anyExchange().authenticated()`) — no Organiser role needed.

| Method | Path | Purpose | Success | Failure |
|---|---|---|---|---|
| `GET` | `/` | Homepage: left column (Participant status + assigned Group/Topic if any + Register-or-Revoke action) and right column (rendered homepage Content Page) | 200, `home/index` view | — (always renders; empty-state right column if no homepage page designated, per Edge Cases) |
| `POST` | `/register` | Self-register the current user as an Active Participant (FR-003), or reactivate their existing record if it is not already Active (FR-007) | Redirect 303 → `/` with a flash confirmation banner (FR-003a) — covers all three success cases: no prior record (created Active), an existing non-Active record (e.g. Revoked — reactivated to Active in place, FR-007), and an existing Active record (idempotent no-op per Edge Cases, still shown as a normal confirmation, not an error) | Redirect 303 → `/` with a flash error only if self-registration is currently disabled (FR-006) |
| `POST` | `/revoke` | Self-revoke the current user's registration (FR-004) | Redirect 303 → `/` with a flash confirmation; Group membership removed if present (FR-007a) | Redirect 303 → `/` with a flash error if self-revocation is currently disabled (FR-006) or the user has no Participant record |

## Behavioral notes

- `POST /register` and `POST /revoke` re-check `OrganiserSettings` on every call — never trust what the
  requesting page rendered at load time (FR-006, Edge Cases).
- `POST /register` branches on the caller's *existing* Participant record, if any, not merely whether one
  exists (data-model.md "Participant"): no record → create Active (FR-003); an existing record whose status is
  not `ACTIVE` (in particular `REVOKED`, reachable via `POST /revoke`) → that same record is updated back to
  `ACTIVE` in place, never a new row (FR-007); an existing `ACTIVE` record → no-op (Edge Cases' double-submit
  case). None of these three is an error response — only a currently-disabled setting is.
- The Revoke action is only ever submitted from behind the client-side `<dialog>` confirmation (FR-004a,
  FR-035; research.md §8) — the server route itself performs the state change unconditionally once POSTed;
  the confirmation step is a UI affordance, not a second server-side gate.
- `GET /` always renders successfully: no Participant record → "Register" action (if enabled) with the
  explanatory line (FR-003a); a Participant record → status, assigned Group/Topic if any, and "Revoke
  Registration" (if enabled); both settings disabled → neither action, status only.
- Assigned Group/Topic display reuses 002's existing `group_members`/`groups`/`topics` read paths — no new
  query beyond "the participant's current active Group, if any, and that Group's Topic."

# Quickstart: Core Domain Model & Organiser Management

This feature has no production OIDC provider configured — automated tests don't need one (see
[research.md](research.md) §6) — so the primary validation path is the automated `WebTestClient` integration
suite driven with Spring Security's reactive mock-login support, run against the real
`SecurityWebFilterChain`, real R2DBC repositories, and a Testcontainers PostgreSQL instance — the same pattern
established for `ActuatorHealthIT` in 001. Constitution Development Workflow #3 additionally **requires** a
visual smoke-test of the rendered Thymeleaf pages before the feature is considered complete; since every
organiser page sits behind OIDC login, a minimal dev-only identity provider (Dex) is included solely for that
manual check — see "Manual visual smoke test" below, which is mandatory, not optional.

## Prerequisites

- Java 25, Maven, Docker (for Testcontainers / the existing `docker-compose.yml` Postgres service) — all
  already required by 001.
- This feature's schema additions applied (extends `src/main/resources/schema.sql`, loaded automatically via
  `spring.sql.init.mode=always`).

## Running the automated validation suite

```bash
mvn verify
```

This runs the unit tests (service-layer invariants) and the `*IT` integration tests (via Failsafe, per the 001
convention) against a real Testcontainers PostgreSQL instance. There is no dedicated UUID-generator test suite:
PostgreSQL 18's native `uuidv7()` column default (research.md §1) needs no application code to test. Each user
story below maps to one or more integration test classes to be created in `tasks.md`/implementation:

| Story | Acceptance Scenario(s) | Integration test (to be created) |
|---|---|---|
| 1 — Identity & Role Recognition | 1–3 | `organiser/user/UserManagementIT` (`mockOidcLogin()` first-time login → Standard; `mockUser()` Organiser toggles another user's privilege; revoked privilege denies the next request) |
| 2 — Skills & Custom Fields | 1–5 | `organiser/skill/SkillManagementIT` (create/rename Skill, duplicate-name rejection, delete-guard) and `organiser/customfield/CustomFieldManagementIT` (free-text field, multi-select field with options, type-lock, option/definition delete-guards) |
| 3 — Participant Records | 1–4 | `organiser/participant/ParticipantManagementIT` (register, status change, skill add/remove, custom field value validation) |
| 4 — Topics | 1–3 | `organiser/topic/TopicManagementIT` (create/view/edit, skill associations, creator persisted) |
| 5 — Groups | 1–5 | `organiser/group/GroupManagementIT` (create, membership add/remove, duplicate-Topic rejection, disband + history) |

Each test authenticates via `SecurityMockServerConfigurers.mockOidcLogin()`/`.mockUser()` (no live IdP
required, per [research.md](research.md) §6) and exercises the routes documented in `contracts/`.

## Expected outcomes (traces to Success Criteria)

- SC-001: `UserManagementIT`'s first-login case asserts a `users` row exists with `organiser = false`
  immediately after one simulated login — no manual step in between.
- SC-004: Every `*ManagementIT` class includes a case with `mockUser()` holding only `ROLE_USER` (no
  `ROLE_ORGANISER`) hitting each route in the relevant `contracts/*.md` file and asserting a non-2xx
  (redirect-to-denied or 403) response.
- SC-006: `UserManagementIT`'s first-login case asserts the generated `User.id` is a valid version-7 UUID
  (version nibble `7`, IETF variant bits) — proving PostgreSQL's `uuidv7()` column default (research.md §1) is
  wired correctly; uniqueness is guaranteed by the `PRIMARY KEY` constraint itself. This single check is
  deliberately treated as representative of all six UUIDv7 tables (`users`, `participants`, `skills`,
  `custom_field_definitions`, `topics`, `groups`): every one declares the identical `id uuid PRIMARY KEY
  DEFAULT uuidv7()` DDL pattern (data-model.md), so verifying the mechanism once is sufficient — a
  version-nibble assertion repeated per entity would test the same Postgres behavior five more times, not
  five different behaviors.
- SC-007: `ParticipantManagementIT` asserts the `/organiser/participants` list response body flags a
  Participant with an unmet required Custom Field, without a per-record follow-up request.

## Manual visual smoke test (required — Constitution Development Workflow #3)

Automated tests never render a page in a real browser, so this step is what actually satisfies the
constitution's "Thymeleaf templates MUST be validated against the running application" rule. It uses the
dev-only Dex identity provider (`dex/config.yaml`, `docker-compose.yml`) added specifically for this purpose —
not used by any automated test.

1. `docker compose up db dex` (starts PostgreSQL and Dex; Dex is published on `localhost:5556`).
2. Export the OIDC client env vars pointing at Dex, then run the app on the host:
   ```bash
   export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DEX_CLIENT_ID=hackathon-organiser
   export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DEX_CLIENT_SECRET=dev-secret
   export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DEX_SCOPE=openid,profile,email
   export SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_DEX_ISSUER_URI=http://localhost:5556/dex
   export SPRING_R2DBC_URL=r2dbc:postgresql://localhost:5432/hackathon
   export SPRING_R2DBC_USERNAME=hackathon
   export SPRING_R2DBC_PASSWORD=hackathon
   mvn spring-boot:run
   ```
3. Visit `/oauth2/authorization/dex`, sign in as the Dex static user (`organiser@example.dev` /
   `password`) → confirm you land authenticated and `/organiser/**` is denied (Standard role only, no
   Organiser flag yet).
4. Manually flip that user's `organiser` column to `true` in Postgres (no self-service path exists yet — by
   design, per this feature's Assumptions), log out/in again, and confirm `/organiser/users` is now reachable.
5. Visually walk each of the six organiser sections (Users, Skills, Custom Fields, Participants, Topics,
   Groups) in the browser — list, create/edit form, and detail pages — following the Story 1–5 acceptance
   scenarios in [spec.md](spec.md) and the route tables in `contracts/*.md`.

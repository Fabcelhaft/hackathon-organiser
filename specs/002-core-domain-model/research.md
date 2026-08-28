# Phase 0 Research: Core Domain Model & Organiser Management

All items below were flagged for research from the Technical Context (Feature 002 is the first feature to add
authentication, SSR views, and a real schema on top of the 001 infrastructure bootstrap). Each entry follows
Decision / Rationale / Alternatives considered.

## 1. UUID v7 generation (FR-025)

**Decision**: Rely on PostgreSQL 18's native, built-in `uuidv7()` function as the column-level `DEFAULT` for
every UUIDv7 primary key — e.g. `id uuid PRIMARY KEY DEFAULT uuidv7()` — rather than generating the value in
application code. Spring Data R2DBC entities declare `@Id private UUID id` left `null` when constructing a new
instance; the R2DBC driver omits a `null` `@Id` column from the generated `INSERT`, Postgres fills it via the
default, and Spring Data R2DBC reads the generated value back off the `INSERT ... RETURNING id` it already
issues for `@Id`-annotated columns. No application method is ever called to produce an identifier.

**Rationale**: PostgreSQL 18 — already the version pinned in [docker-compose.yml](../../docker-compose.yml)
(`postgres:18.6-alpine`) — ships a native, RFC 9562 §5.7-compliant `uuidv7()` function with no extension and no
custom PL/pgSQL required. This is strictly simpler than any application-side approach: zero dependency surface
(Constitution Principle I's "minimise dependency surface" concern doesn't even arise), one line of DDL per
table instead of a generator class plus its own unit test, and the database becomes the single source of truth
for identity generation — correct even for rows inserted outside the application (manual fixes, future
migration scripts) without relying on every code path remembering to call a generator.

**Alternatives considered**:
- An in-house Java generator (the original decision here, superseded) — was based on the incorrect premise
  that "Postgres has no built-in UUIDv7 function as of PG 18." PG 18 specifically added `uuidv7()`, which makes
  a hand-rolled generator pure duplication of behavior the database already provides natively.
- `com.github.f4b6a3:uuid-creator` / `com.fasterxml.uuid` (Java Uuid Generator) — rejected for the same reason
  as before, now more clearly unnecessary: an extra dependency (or hand-written class) for something the
  database does out of the box.
- A custom PL/pgSQL function or the `pgcrypto`/`uuid-ossp` extensions — unnecessary now that PG 18 ships
  `uuidv7()` directly as a built-in.

## 2. OIDC authentication & role derivation (FR-001–FR-005, edge case on privilege revocation)

**Decision**: Add `spring-boot-starter-oauth2-client` and configure a reactive `SecurityWebFilterChain` with
`.oauth2Login(...)`. Provide a custom `ReactiveOAuth2UserService<OidcUserRequest, OidcUser>`
(`HackathonOidcUserService`) that, on every successful login, upserts a `User` row keyed by the OIDC `sub`
claim — creating it on first login (FR-002) and refreshing `display_name`/`email` from the latest claims on
every login (edge case: reconcile on stable subject id, not mutable profile fields). The service wraps the
result in a custom `OidcUser` (`HackathonOidcUser`) that re-reads the `organiser` flag from the just-upserted
row and grants a `ROLE_ORGANISER` authority when true. Path authorization is
`.pathMatchers("/organiser/**").hasRole("ORGANISER")`, everything else `.authenticated()`.

**Rationale**: This is the standard, fully-native Spring Security reactive pattern (Constitution I). It
satisfies FR-001 (Spring Security's OAuth2 client never persists a local password) and FR-003 (every
authenticated user implicitly has Standard — no separate assignment). Re-deriving `ROLE_ORGANISER` from the
database on every login (rather than caching it for the life of a long session/JWT) is what makes the
"privilege revoked mid-session" edge case work: a fresh login (or, if session-based, the next
`ReactiveOAuth2UserService` invocation) re-reads the current DB value.

**Alternatives considered**:
- Hand-rolled `WebFilter` doing manual token exchange/validation — reinvents Spring Security for no benefit
  and risks introducing bugs Spring Security's OIDC support already handles correctly.
- Baking the organiser role into the ID token/JWT claims and trusting it directly — rejected because the IdP,
  not this database, would then be the source of truth for the Organiser privilege, contradicting FR-005
  ("derive the Organiser role solely from the database-stored ... privilege").

## 3. Thymeleaf/WebFlux organiser path & package separation (FR-024)

**Decision**: All organiser-only Spring MVC-style controllers (`@Controller`, WebFlux-reactive) and their
Thymeleaf templates live under one top-level Java package / template directory —
`net.fabcelhaft.hackathonorganiser.organiser` / `templates/organiser/**` — with every route prefixed
`/organiser/**`, satisfying FR-024. Below that single root, controllers are grouped by *business domain*
rather than bundled into one flat technology-named bucket: `organiser.user.UserController`,
`organiser.participant.ParticipantController`, `organiser.skill.SkillController`,
`organiser.customfield.CustomFieldController`, `organiser.topic.TopicController`,
`organiser.group.GroupController`. The domain entities, repositories, and services these controllers call are
organized the same way, one package per business domain (`user`, `participant`, `skill`, `customfield`,
`topic`, `group`) holding that domain's entity/repository/service together, instead of technology-layered
`domain`/`repository`/`service` packages — since those are not organiser-exclusive, they sit alongside
`organiser` rather than under it.

**Rationale**: The single `organiser` package root directly satisfies FR-024's "distinct path and
package... separate from the rest of the application" for the *views*. Splitting both the organiser web layer
and the shared domain/service/repository layer by business domain (rather than by technical layer) keeps
everything relevant to one concept — say, Topic — in one place (`topic/Topic.java`,
`topic/TopicRepository.java`, `topic/TopicService.java`, `organiser/topic/TopicController.java`) instead of
scattered across four parallel `domain`/`repository`/`service`/`organiser.web` trees that only line up by
matching filenames. It also keeps the reusable per-domain layer available to the participant-facing "finetuned
logic" the spec's own Input explicitly defers to future specs (Assumptions section: self-service registration,
topic creation, and team formation are out of scope here but expected later) without forcing a package move
when that logic arrives — a future participant-facing `TopicController` would simply live in a new
`participant-facing` (or similarly named) sibling package next to `organiser`, reusing the same `topic`
domain package. `security` remains its own top-level package: it is cross-cutting infrastructure (wires
`/organiser/**` authorization for every business domain alike, not just one), not itself a business domain.

**Alternatives considered**:
- One flat `organiser.web` package holding all six controllers, and flat `domain`/`repository`/`service`
  packages holding all entities/repositories/services (the original decision here, superseded) — technology
  layering makes every cross-cutting change (e.g., touching everything related to Topic) require editing four
  unrelated packages, and gives no package-level signal of which classes belong to the same business
  capability.
- Moving the entire domain model under `organiser` too — rejected for the same reason as before: it would
  force a package move in the very next (participant-facing) feature.
- A separate Maven module per business domain — rejected: the constitution mandates a single reactive Spring
  Boot application, and this feature's scope doesn't justify multi-module build complexity.

## 4. Business invariant enforcement

Five invariants need enforcement: at most one Participant per User (FR-006a); at most one *active* Group per
Topic (FR-016a) while a disbanded Group's history remains (FR-016b); at most one active Group per Participant
(FR-017); a Custom Field's type is locked once any Participant has a value for it (FR-012a); a Skill/Custom
Field/Custom Field Option cannot be removed while referenced (FR-023, FR-012b).

**Decision**:
- "At most one Participant per User" → a plain `UNIQUE` constraint on `participants.user_id`.
- "At most one active Group per Topic" and "at most one active Group per Participant" → **Postgres partial
  unique indexes**: `UNIQUE (topic_id) WHERE status = 'ACTIVE'` on `groups`, and `UNIQUE (participant_id)
  WHERE active` on `group_members` (an `active` boolean on the membership row, set to `false` for every member
  when their group is disbanded, alongside the group's own `status` flip to `DISBANDED`).
- Custom Field type-lock (FR-012a) and delete-guard (FR-023, FR-012b) → **service-layer pre-check** inside the
  same reactive chain as the mutation (query for existing references, short-circuit with a domain error before
  attempting the write), backed by the database's default `NO ACTION` foreign-key behavior as a defence-in-depth
  safety net against any code path that skips the service layer.

**Rationale**: Partial unique indexes give a correctness guarantee a "check-then-write" `Mono` chain cannot:
two concurrent organiser requests both passing an application-level check would otherwise both succeed. The
type-lock and delete-guard rules need a friendly, actionable message ("clear these references first") rather
than a raw constraint-violation exception surfacing to the organiser, so those two stay primarily at the
service layer.

**Alternatives considered**:
- Enforcing the "at most one active Group" rules only in the service layer via SELECT-then-write — rejected as
  race-prone under concurrent organiser edits, which the partial-unique-index approach makes structurally
  impossible.
- A single non-partial unique constraint on `groups.topic_id` / `group_members.participant_id` regardless of
  status — rejected: it would make it impossible to keep a disbanded Group's historical row while a new Group
  forms for the same Topic/Participant, contradicting FR-016b.

## 5. Pico CSS delivery (Constitution IV)

**Decision**: Vendor the Pico CSS minified stylesheet under `src/main/resources/static/css/pico.min.css`,
referenced from a shared Thymeleaf layout fragment via `th:href="@{/css/pico.min.css}"`.

**Rationale**: The constitution explicitly permits either a CDN link or a vendored static asset. Vendoring
avoids a runtime dependency on an external CDN being reachable, which matters for a self-hosted, potentially
offline-venue hackathon-day tool, and keeps the existing Dockerfile-based deployment from 001 fully
self-contained.

**Alternatives considered**: CDN `<link>` — rejected to avoid an external network dependency for page styling
and to keep the container image self-sufficient.

## 6. Testing OIDC-gated views without a live identity provider (Constitution V)

**Decision**: Integration tests use `spring-security-test`'s reactive support
(`SecurityMockServerConfigurers.mockOidcLogin()` / `.mockUser()`) with `WebTestClient` to simulate an
authenticated Standard user and an authenticated Organiser against the *real* `SecurityWebFilterChain` and its
`/organiser/**` authorization rule — no live external IdP involved. `HackathonOidcUserService`'s upsert logic
gets its own focused Mockito unit test plus one Testcontainers-backed integration test exercising it directly
against a real `UserRepository`.

**Rationale**: Matches Constitution V's `WebTestClient`-based integration testing without requiring a real
OIDC provider in CI — keeps tests fast and hermetic while still exercising the actual security filter chain
(not a hand-mocked one). FR-001–FR-005 and FR-022 are about *authorization given a role*, not about
re-validating Spring Security's own well-tested OAuth2 code-exchange handshake.

**Alternatives considered**: Standing up a throwaway OIDC provider (e.g., a Keycloak/Dex Testcontainer) for
full end-to-end login-flow tests — rejected as unnecessary scope for this feature; it would test framework
code this project doesn't own, at a significant CI time/complexity cost.

**Note (added during `/speckit-analyze` remediation)**: this decision covers *automated* tests only.
Constitution Development Workflow #3 separately mandates a manual visual smoke-test of the rendered Thymeleaf
pages, which — unlike an automated test — cannot use `mockOidcLogin()` because it requires an actual browser
login round-trip. A minimal dev-only Dex instance (`dex/config.yaml`, `docker-compose.yml`) is included
specifically for that manual step (quickstart.md "Manual visual smoke test"); it is not wired into any
automated test and does not change the decision above.

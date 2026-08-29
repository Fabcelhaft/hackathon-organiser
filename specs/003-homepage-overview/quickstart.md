# Quickstart: Homepage Overview, Self-Service Registration & Topics

This feature reuses 002's OIDC/test infrastructure exactly (`SecurityMockServerConfigurers.mockOidcLogin()`/
`.mockUser()`, Testcontainers PostgreSQL, `spring.sql.init.mode=always`) and adds exactly one new automated
validation category: browser-driven accessibility scanning (research.md §9), since `WebTestClient` cannot
evaluate a real accessibility tree.

## Prerequisites

- Java 25, Maven, Docker (Testcontainers + `docker-compose.yml` Postgres) — unchanged from 001/002.
- This feature's schema additions applied (extends `src/main/resources/schema.sql`, loaded automatically).
- Playwright's headless Chromium browser binary, installed once via
  `mvn -q exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"` (or the
  Playwright Maven plugin's install goal, wired up in Phase 2 tasks) — needed only for the `a11y.*IT` suite,
  not for the `mvn verify` unit/`*ManagementIT` run.

## Running the automated validation suite

```bash
mvn verify
```

Runs unit tests (service-layer invariants: `OrganiserSettingsServiceTest`, `ContentPageServiceTest`,
`ContentImageServiceTest`, `MarkdownRendererTest`, plus 002's existing suites extended for the new
self-register/self-revoke/approval cases) and `*IT`/`*ManagementIT` integration tests via Failsafe against a
real Testcontainers PostgreSQL, plus the new `a11y.*IT` Playwright+axe-core suite (research.md §9).

| Story | Acceptance Scenario(s) | Test(s) (to be created) |
|---|---|---|
| 1 — Homepage Status & Self-Service | 1–7 | `home/HomeControllerIT` (no-record → Register shown; Active → status/Group/Topic + Revoke shown; register creates immediately, no form; revoke sets Revoked + shows Register again; revoke removes Group membership, preserves history; double-register no-ops) |
| 2 — Organiser Registration/Revocation Controls | 1–4 | `organiser/settings/SettingsManagementIT` (disable/enable each toggle, confirm effect on `/register`/`/revoke`/`/topics`; nav link visible only to `ROLE_ORGANISER`) |
| 3 — Browse/Propose Topics | 1–5 | `topic/TopicSelfServiceManagementIT` (list shows author display-name+OIDC-subject; propose creates with author; author-only edit; non-author denied; own-Pending sorts to top labeled "Pending approval") |
| 4 — Organiser Topic Approval/Admin | 1–5 | `organiser/topic/TopicManagementIT` (extended: enable/disable approval requirement; approve Pending; edit any field incl. author reassignment; multiple Pending grouped/ordered) |
| 5 — Rendered Homepage & Info Content | 1–2 | `info/InfoManagementIT` (homepage right column renders formatted HTML; Info lists pages in arranged order, each opens rendered) |
| 6 — Organiser Content Page Management | 1–4 | `organiser/content/ContentPageManagementIT` (add appears in Info; sort-index reorders for all; remove disappears; non-Organiser sees no management actions) |
| 7 — Organiser Content Image Upload/Embed | 1–7 | `organiser/content/ContentImageManagementIT` (upload → library + embed syntax; embed renders inline; delete-blocked-while-referenced names the page(s); delete succeeds once unreferenced; alt-text edit in place, old embeds unaffected; non-Organiser denied) |

Each `*ManagementIT`/`*IT` class authenticates via `mockOidcLogin()`/`.mockUser()` (no live IdP required) and
exercises the routes documented in `contracts/`.

## Automated accessibility scan (SC-009)

```bash
mvn verify -Dtest=a11y.*IT -DfailIfNoTests=false
```

`a11y.HomepageAccessibilityIT` and its siblings (research.md §9) boot the full app on a random port
(`@SpringBootTest(webEnvironment = RANDOM_PORT)`), drive headless Chromium via Playwright to the homepage,
topic propose/edit forms, this feature's organiser settings/Content Page/Content Image screens, and the Info
section, run `AxeBuilder.analyze()` on each, and assert zero `critical`/`serious` violations. Pre-existing
002 organiser screens (Users, Skills, Custom Fields, Groups) are intentionally excluded (FR-030 scope note).

## Expected outcomes (traces to Success Criteria)

- SC-001/SC-002: `HomeControllerIT` asserts a single `POST /register` call is immediately followed by `GET /`
  reflecting Active status — no Organiser action, no polling, in between.
- SC-003: `SettingsManagementIT` asserts a toggle flip is visible on the *very next* request to `/register` (or
  `/revoke`/`/topics`) with no restart — proving `OrganiserSettingsService` reads fresh, uncached, on every call.
- SC-006: `MarkdownRendererTest` feeds markdown containing a `<script>` tag and asserts the sanitized output
  contains no `<script>`/`on*` attribute, alongside `InfoManagementIT` confirming the rendered response body
  contains formatted tags (`<h2>`, `<ul>`, etc.), never literal `#`/`*` markdown syntax.
- SC-009: the `a11y.*IT` suite (above) is the direct, literal check for this criterion.

## Manual visual smoke test (required — Constitution Development Workflow #3)

Reuses 002's dev-only Dex identity provider exactly as documented in
[../002-core-domain-model/quickstart.md](../002-core-domain-model/quickstart.md#manual-visual-smoke-test-required--constitution-development-workflow-3)
steps 1–4 (start `db`+`dex`, export the same OIDC env vars, `mvn spring-boot:run`, log in, flip `organiser` to
`true` in Postgres for the manual-Organiser step). Then, additionally for this feature:

1. As a non-Organiser Participant: visit `/`, confirm the two-column layout (stacking vertically — status,
   then topics, then content — on a narrow viewport, FR-001a), register, confirm the flash confirmation is
   read by a screen reader on landing (or visually verify the `role="status"` banner and moved focus),
   propose a Topic, confirm it's visible and editable by you, confirm the Revoke `<dialog>` traps focus and
   requires explicit confirmation before it removes your Group membership (if any).
2. As an Organiser: visit `/organiser/settings`, toggle each control and confirm the plain-language effect
   sentence updates; visit `/organiser/content-pages`, create a page, set its sort index, confirm `/info`
   reorders; designate a different page as the homepage page and confirm `/` right column changes; visit
   `/organiser/content-images`, upload an image, paste the provided markdown syntax into a Content Page body,
   confirm it renders inline; attempt to delete that referenced image and confirm the block message names the
   page; remove the reference, delete successfully.
3. With browser dev tools' accessibility inspector (or a screen reader), tab through the Revoke confirmation
   dialog and the topic-propose form to confirm visible focus indicators and a logical tab order (FR-031).

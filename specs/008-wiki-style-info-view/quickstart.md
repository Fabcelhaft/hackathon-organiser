# Quickstart: Wiki-Style Info View

This feature reuses the existing OIDC/test infrastructure unchanged (`SecurityMockServerConfigurers
.mockOidcLogin()`/`.mockUser()` — note `mockOidcLogin()` alone needs a real `User` wired in for
`@AuthenticationPrincipal HackathonOidcUser` routes to resolve, per this codebase's existing IT conventions —
Testcontainers PostgreSQL, `spring.sql.init.mode=always`). No new test category is introduced; it extends the
existing unit/`*ManagementIT`/`*AccessibilityIT` suites already covering Info, Content Pages, Registration, and
Topics.

## Prerequisites

- Java 25, Maven, Docker (Testcontainers + `docker-compose.yml` Postgres) — unchanged.
- The `schema.sql` change in [data-model.md](./data-model.md) (`is_homepage` boolean → `context` text column)
  applied automatically via `spring.sql.init.mode=always`.
- A Chromium binary for the `a11y.*IT` Playwright suite (unchanged setup — see
  [003's quickstart](../003-homepage-overview/quickstart.md#prerequisites) for the devcontainer detail).

## Running the automated validation suite

```bash
mvn verify
```

Runs unit tests and `*ManagementIT`/`*IT` integration tests via Failsafe against a real Testcontainers
PostgreSQL, plus the `a11y.*IT` Playwright+axe-core suite.

| Story | Acceptance Scenario(s) | Test(s) |
|---|---|---|
| 1 — Browse info pages like a wiki | 1–5 | `info/InfoManagementIT` (rewritten for the merged wiki view: default page shown at `/info`, menu + content both present at `/info/{id}`, `aria-current` on the selected entry, same-index pages ordered alphabetically, unknown/removed/designated id → 404 status with menu still rendered) |
| 2 — Organisers author from the wiki view | 1–5 | `info/InfoManagementIT` (Edit/New page links present for an organiser-authenticated request, absent for a plain participant; both links target `/organiser/content-pages/**` with `target="_blank"`) |
| 3 — Context-specific special pages | 1–10 | `content/ContentPageServiceTest` (context exclusivity: designating a second page for a held context clears the first; a page's context is independent of the other two contexts) plus `organiser/content/ContentPageManagementIT` (single-select control, organiser overview shows designation, delete confirmation prompt naming the context), `participants/RegistrationManagementIT` (designated body renders above the form; absent when none designated), `topics/TopicSelfServiceManagementIT` (designated body renders on `/topics/new` only, never on `/topics/{id}/edit` or the organiser equivalents) |
| 4 — Info section with no pages yet | 1–3 | `info/InfoManagementIT` (empty-state message for a participant; empty-state + "New page" action for an organiser) |

Each test class authenticates via `mockOidcLogin()`/`.mockUser()` (no live IdP required) and exercises the
routes documented in [contracts/wiki-info-and-content-pages.md](./contracts/wiki-info-and-content-pages.md).

Additionally (regression coverage for behaviour this feature must **not** change):

- `home/HomeControllerIT` — homepage right column still renders the `HOMEPAGE`-context page unchanged.
- `content/MarkdownRendererTest` — untouched; heading-shifting/sanitization already guarantee FR-016c
  (designated content's headings stay subordinate to the host page's own heading) with no new logic.

## Automated accessibility scan

```bash
mvn verify -Dtest=a11y.*IT -DfailIfNoTests=false
```

Add an `a11y.InfoAccessibilityIT` alongside the existing `HomepageAccessibilityIT`/`RegistrationAccessibilityIT`/
`TopicDetailAccessibilityIT`/`TopicOverviewAccessibilityIT` siblings, covering the new persistent
menu-plus-content-pane layout (a landmark nav with `aria-current`, one `<h1>` per rendered page) — this is new
markup, not a modification of markup an existing a11y test already covers.

## Expected outcomes (traces to Success Criteria)

- SC-001/SC-002: `InfoManagementIT` asserts a single `GET /info/{id}` request renders both the menu and that
  page's content in one response, with the requested id's menu entry carrying `aria-current="page"` — no
  intermediate list-page request in the path.
- SC-003: `InfoManagementIT` (organiser case) asserts the Edit link's `href` targets exactly the currently
  displayed page's id and carries `target="_blank"`.
- SC-004: `InfoManagementIT` (participant case) asserts the response body contains neither the Edit nor New
  page link markup at all (not merely hidden/disabled).
- SC-005: `InfoManagementIT` (empty-state case) asserts the empty-state message, with zero `ContentPage` rows
  present in the database for that test.
- SC-006: `content/ContentPageServiceTest` asserts designating a page for a held context clears the previous
  holder in the same operation, and that a designated page never appears in `findInfoList()`'s output.
- SC-007: `RegistrationManagementIT`/`TopicSelfServiceManagementIT` each assert both branches — designated
  content present when set, byte-identical form markup to today when not.
- SC-008: `InfoManagementIT` asserts two same-`sort_index` pages always appear in the same (title-alphabetical)
  order across repeated requests.

## Manual visual smoke test (required — Constitution Development Workflow #3)

Reuses the existing dev-only Dex identity provider setup documented in
[002's quickstart](../002-core-domain-model/quickstart.md#manual-visual-smoke-test-required--constitution-development-workflow-3)
steps 1–4. Then, additionally for this feature:

1. As a non-Organiser participant: visit `/info`, confirm the left-hand menu and right-hand content render
   together with no intermediate list page; click another menu entry and confirm the content pane updates and
   the menu highlights the new current entry; confirm no Edit/New page action is visible anywhere.
2. As an Organiser: repeat the above and confirm Edit/New page links appear above the content, each opening in
   a new tab while the wiki tab stays open and unchanged.
3. As an Organiser, in `/organiser/content-pages/new`: confirm the Sort Index field arrives pre-filled one
   above the current highest index; designate the new page for "Topic creation", save, then confirm it no
   longer appears in `/info`'s menu and its content appears above the topic-creation form at `/topics/new` but
   not at `/topics/{id}/edit`.
4. As an Organiser, delete a page currently designated for a context and confirm the browser confirmation
   names that context; confirm the corresponding form (registration, topic creation, or homepage) renders with
   no designated content afterwards.
5. With zero undesignated pages (delete or designate every page): confirm `/info` shows the empty-state
   message, with the "New page" action visible only when signed in as an Organiser.

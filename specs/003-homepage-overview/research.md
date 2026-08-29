# Phase 0 Research: Homepage Overview, Self-Service Registration & Topics

The feature spec's own Clarifications session already resolved every open product question, so there are no
`NEEDS CLARIFICATION` markers in Technical Context. What remains is a set of implementation-technology and
integration decisions this feature needs that 002 never had to make (markdown rendering, binary storage,
automated accessibility scanning, and how to add role-aware navigation without contradicting a prior decision
in `SecurityConfig`). Each entry follows Decision / Rationale / Alternatives considered.

## 1. Markdown rendering & sanitization (FR-017, FR-022, FR-026, FR-036)

**Decision**: Parse markdown with `org.commonmark:commonmark:0.24.0` (a small, dependency-free CommonMark
parser), then sanitize the resulting HTML with `com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20240325.1`
using a policy built from `Sanitizers.FORMATTING.and(Sanitizers.LINKS).and(Sanitizers.IMAGES)` (headings,
emphasis, lists, links, and `<img>` with `src`/`alt` — matching FR-017's minimum and FR-026's inline-image
requirement) before the HTML is ever placed into a Thymeleaf model via `th:utext`. A single
`MarkdownRenderer` component wraps both steps so every call site (homepage right column, Info pages) goes
through the same pipeline — there is exactly one place in the codebase that ever emits unescaped HTML.

For FR-036 (the page's own title must be the top-level heading; markdown-authored headings render at or below
the next level): commonmark-java has no built-in "heading offset" option, so `MarkdownRenderer` supplies a
custom `HtmlNodeRendererFactory` that intercepts `Heading` nodes and renders `min(level + 1, 6)` instead of the
raw parsed level — i.e. a markdown `#` becomes `<h2>`, `######` stays `<h6>` (capped, per HTML's own heading
ceiling) — so every rendered page keeps one logical top-level heading (the Thymeleaf-rendered page/section
title) regardless of what the Organiser writes in the markdown body.

**Rationale**: commonmark-java is the reference CommonMark implementation for the JVM, has zero transitive
dependencies (Constitution I's "minimise dependency surface"), and its extensible `HtmlNodeRendererFactory`
API is exactly what the heading-offset requirement needs without forking the renderer. The OWASP sanitizer is
the de-facto standard allowlist-based HTML sanitizer for Java, actively maintained, and lets FR-022's "strip
all executable content" be expressed as a policy rather than hand-rolled regex stripping (which is exactly the
kind of homegrown security-sensitive parsing this codebase should avoid per "Be careful not to introduce
security vulnerabilities").

**Alternatives considered**:
- `flexmark-java` — more features (tables, footnotes, etc.) than FR-017's stated minimum needs; heavier
  dependency surface for capability this feature doesn't require.
- Hand-rolled regex-based script stripping instead of an allowlist sanitizer — rejected outright: denylist
  HTML sanitization is a well-known source of XSS bypasses; an allowlist policy is the correct approach and is
  what OWASP's own sanitizer provides.
- Rendering markdown client-side (e.g. a JS library) — forbidden by Constitution III (server-side rendering
  only) and would also move the sanitization boundary to the browser, defeating FR-022's "before display"
  requirement.

## 2. Content Image storage & serving (FR-024, FR-025, FR-026)

**Decision**: Store each `Content Image`'s binary as a `bytea` column on `content_images` (not the
filesystem, per FR-024), plus its `content_type` (one of `image/png`, `image/jpeg`, `image/gif`,
`image/webp`), `byte_size`, and required `alt_text`. Serve the raw bytes via a new, non-`/organiser/**` route
`GET /content-images/{id}` (package `web.ContentImageStreamController`) that reads the row through R2DBC and
writes the response with `Content-Type` set from the stored `content_type` and a long-lived `Cache-Control`
header (the bytes never change in place — only alt text is editable per FR-025b, and that isn't served from
this route). The markdown syntax an Organiser copies (FR-025) is
`![<alt text>](/content-images/{id})`, i.e. the stable reference *is* this route's path.

**Rationale**: FR-024 explicitly mandates database storage over the filesystem. A dedicated streaming route
(rather than embedding images as base64 data URIs in the rendered HTML) keeps rendered page payloads small and
lets the browser cache images independently across the homepage and every Info page that might reference the
same one. The route must sit outside `/organiser/**` because any authenticated user (not just Organisers)
needs to load images embedded in pages they're allowed to view (Info pages and the homepage are visible to
all authenticated users per the spec's Assumptions) — `SecurityConfig`'s existing `.anyExchange().authenticated()`
default already covers it correctly with no new security rule needed.

**Alternatives considered**:
- Base64 data URIs inlined directly into the sanitized HTML — rejected: bloats every page load, defeats
  browser caching, and complicates the OWASP sanitizer policy (data URIs in `src` need their own allowlisting
  logic, which is exactly the kind of extra security-sensitive surface to avoid).
- A filesystem-backed static-asset path (`src/main/resources/static/...`) — directly contradicts FR-024.

## 3. Content Image deletion-block detection (FR-028)

**Decision**: No separate join table tracking "which Content Pages reference which Content Images." Detection
is a simple substring search: on a delete attempt, `ContentImageService` queries
`SELECT id, title FROM content_pages WHERE body_markdown LIKE '%/content-images/' || :imageId || '%'`
and, if any rows come back, rejects the delete with a `ContentImageConflictException` naming those pages'
titles (satisfying "the Organiser is told which page(s) reference it").

**Rationale**: The embed mechanism is literal — an Organiser pastes the exact `/content-images/{id}` path
into a markdown body — so the reference is always present verbatim in `body_markdown` if it exists at all.
A join table would need to be kept in sync on every Content Page save (parsing the markdown for image
references, diffing against the previous set), which is real complexity for a check only exercised at
image-delete time, in a codebase whose stated preference (per `GroupService`/`ParticipantService`) is to keep
association bookkeeping as simple direct queries when the data volume is small (hackathon-scale, not
web-scale).

**Alternatives considered**:
- A `content_page_image_refs` join table maintained on every `ContentPage` save — rejected as premature
  complexity for a check the spec only requires at delete time; revisit only if reference-integrity needs grow
  beyond a single delete-guard.
- Parsing the sanitized/rendered HTML instead of the raw markdown — unnecessary extra work; the raw
  markdown source already contains the literal path.

## 4. Organiser Settings: singleton row & startup seeding (FR-023, FR-023a)

**Decision**: `organiser_settings` carries a `singleton boolean NOT NULL DEFAULT true` column with
`CREATE UNIQUE INDEX organiser_settings_singleton_key ON organiser_settings (singleton)` — the same
"partial/expression unique index as a concurrency-safe invariant" pattern 002 already uses for
`groups_topic_id_active_key` and `group_members_participant_id_active_key`, just unconditional instead of
partial (there's only ever one row, full stop). Seeding is one idempotent statement appended to `schema.sql`
(which already reruns on every startup via `spring.sql.init.mode=always`):
`INSERT INTO organiser_settings (singleton) VALUES (true) ON CONFLICT (singleton) DO NOTHING;` — no
`CommandLineRunner`/`ApplicationRunner` bean needed. `OrganiserSettingsRepository` exposes a single derived
finder, `findBySingletonTrue()`, and `OrganiserSettingsService` reads through it on every settings check (no
in-memory caching, so a toggle takes effect on the very next request per FR-023/SC-003 with no extra
invalidation logic to get wrong).

**Rationale**: This mirrors an already-established, reviewed pattern in this codebase exactly (unique index as
the concurrency backstop for a cardinality invariant) rather than introducing a new one. Seeding via
`schema.sql` keeps FR-023a's "runs as part of application initialisation... not a one-off manual migration" in
the same place 002 already puts all schema/lifecycle concerns, instead of adding a second seeding mechanism.

**Alternatives considered**:
- A `CommandLineRunner` bean performing a `findAll().hasElements()` check-then-insert — rejected: introduces
  a second, less concurrency-safe seeding mechanism (a race between two application instances starting
  simultaneously) when `schema.sql`'s `ON CONFLICT DO NOTHING` already handles that atomically at the database
  level, for free.
- A single-column `id boolean PRIMARY KEY DEFAULT true CHECK (id)` singleton trick — rejected only for
  consistency: every other table in this codebase uses a `uuid` PK, and deviating here for no functional
  gain would be a needless inconsistency.

## 5. Default homepage Content Page seeding (FR-019a)

**Decision**: A second idempotent `schema.sql` statement:
```sql
INSERT INTO content_pages (title, body_markdown, sort_index, is_homepage)
SELECT 'Welcome', '# Welcome to the Hackathon', 0, true
WHERE NOT EXISTS (SELECT 1 FROM content_pages);
```
This only fires when the table is completely empty (first-ever startup), never again afterward — even if the
seeded page is later deleted, re-seeding on the *next* restart is explicitly not desired (an Organiser who
deletes the placeholder made a deliberate choice; FR-019a only promises "on first application startup").

**Rationale**: Same reasoning as §4 — reuse the one seeding location the codebase already has, guarded by a
`WHERE NOT EXISTS` condition instead of a unique index (there's no fixed cardinality invariant to enforce here,
just a one-time bootstrap condition).

**Alternatives considered**: A `CommandLineRunner` — rejected for the same "why add a second mechanism"
reasoning as §4.

## 6. Topic approval workflow & the homepage's 3-group ordering (FR-009a, FR-012–FR-016)

**Decision**: `topics` gains `approval_status text NOT NULL DEFAULT 'APPROVED'`
(`TopicApprovalStatus`: `PENDING` | `APPROVED`), set explicitly at creation time by `TopicService.propose(...)`
based on the current `OrganiserSettings.topicApprovalRequired` value read at that moment (never retroactive,
per FR-016). FR-009a's three-group, per-group-by-creation-date ordering is computed in
`TopicService`/a new read-model method, not in SQL: fetch the viewer-visible Topics (already a small,
hackathon-scale result set, consistent with how `ParticipantService`/`GroupService` already favor
straightforward Java-level composition — e.g. `findAllSummaries()` — over complex joins), then partition with
plain `Comparator`/`Stream` grouping into the three ordered buckets FR-009a defines, keyed off
`(approvalStatus, authorUserId == viewerUserId)`.

**Rationale**: A single `ORDER BY CASE ...` SQL expression could technically encode the three groups, but the
per-viewer branch ("is this topic's author the current viewer") makes the SQL viewer-dependent, which this
codebase's existing repositories deliberately avoid (every `*Repository` here is a plain `ReactiveCrudRepository`
with simple derived finders; viewer-specific business logic consistently lives in the `*Service` layer, as
seen in `ParticipantService.findUsersWithoutParticipant()`'s `filterWhen`). Keeping the grouping in Java keeps
the SQL simple and the visibility/ordering rule unit-testable without a database.

**Alternatives considered**: A parameterized SQL `ORDER BY` with the viewer id bound in — rejected as an
unnecessary SQL/Java split of one cohesive business rule that's cheap to compute in memory at this data scale.

## 7. Role-aware shared navigation without a new Thymeleaf Spring Security dialect (FR-008, FR-008a)

**Decision**: Do **not** add `thymeleaf-extras-springsecurity6`. `SecurityConfig`'s existing code comment
already records a deliberate prior decision not to have that dialect on the classpath (it's why CSRF is
currently disabled — no dialect means no CSRF hidden-field support in forms). Reopening that trade-off is out
of scope for this feature. Instead, a new `@ControllerAdvice`
(`web.CurrentUserModelAdvice`) with a reactive `@ModelAttribute` method resolves the current
`HackathonOidcUser` from the exchange's `SecurityContext` and injects two model attributes —
`currentUser` (nullable — some pages may be reached only when authenticated, but this keeps the advice
reusable) and `isOrganiser` (boolean) — into every view rendered by an annotated controller. The shared layout
fragment's `th:if="${isOrganiser}"` conditionally renders the Organiser nav link (FR-008), with an accessible
icon+text label, never color alone (FR-008a).

**Rationale**: Keeps the security-dialect decision exactly as 002 left it (least change, no re-litigating a
settled trade-off) while still giving every template the boolean it needs. `@ControllerAdvice` with
`@ModelAttribute` is itself a "native Spring Boot/Spring MVC-family feature" (Constitution I), works
identically for WebFlux annotated controllers, and centralizes the logic in one place instead of every
controller re-deriving `isOrganiser` from `@AuthenticationPrincipal` by hand.

**Alternatives considered**:
- Add `thymeleaf-extras-springsecurity6` for `sec:authorize` — rejected: reopens a settled decision (CSRF)
  for a small ergonomic win, when a `@ControllerAdvice` gets the same behavioral result with zero net new
  dependencies.
- Have every controller pass `isOrganiser` explicitly — rejected: every single controller in the app (existing
  and new) would need the same three lines of boilerplate; a single cross-cutting advice is the DRY choice
  Spring already offers natively.

## 8. Accessible confirmation dialog & non-reload status messaging within an SSR-only constitution (FR-033, FR-035)

**Decision**: The Revoke-Registration confirmation (FR-004a, FR-035) uses the native HTML5 `<dialog>` element,
opened via `.showModal()` — which gives built-in focus trapping and Escape-to-close for free, satisfying
"programmatically identified as a dialog," "trap keyboard focus," and (by returning focus to the triggering
button in the `close` handler) "return focus to a logical location" — triggered by a handful of lines of
vanilla `<script>` (no bundler, no framework, Constitution III's ban is specifically on client-side *rendering
frameworks*). The actual Revoke action still submits as a normal HTML form POST once confirmed.

For FR-033 ("a status change... MUST be announced... via an appropriately scoped live region"): because
Constitution III mandates full server-side rendering with no SPA-style partial updates, every state change in
this feature (register, revoke, approve, settings toggle) is realized as a classic POST → redirect → GET, i.e.
a full page navigation, not an in-page DOM mutation — so there is no moment in this architecture where a
"status change occurs without a full page reload" in the literal SPA sense. The practical realization is: the
redirect target renders a flash-message banner (`role="status" aria-live="polite"`, e.g. "Registration
successful") as part of that page's initial HTML, and the page's initial focus is moved to that banner (a
`tabindex="-1"` element `.focus()`-ed by a small inline script on load) so a screen reader user lands on the
confirmation immediately rather than at the top of a re-rendered nav. This satisfies FR-033's intent — the
result of the action is programmatically announced, not conveyed by a visual flash alone — within the
constraints Constitution III actually imposes. Per the spec's own Assumptions ("manual screen-reader and
keyboard-only testing is still expected before this feature ships"), this behavior is verified manually, not
by the axe-core scan in §9 (static analysis tools cannot evaluate focus-on-load behavior).

**Rationale**: Keeps every interaction inside the SSR model the constitution requires while still meeting the
accessibility requirements' actual intent (a screen reader user must be told what happened, not left to
re-discover it visually) rather than either (a) silently dropping FR-033 as "inapplicable" or (b) violating
Constitution III to add a JS framework capable of true in-page live-region updates.

**Alternatives considered**:
- A small amount of `fetch()`-based partial-page JS actually mutating an in-page live region without
  navigating — rejected: still avoids a *framework*, but adds real client-side state-management logic this
  team has explicitly chosen not to build (Constitution III's rationale is "avoids a separate frontend build
  pipeline... important for accessibility"); the flash-message-plus-focus pattern gets the same user-facing
  outcome with zero JS beyond the dialog itself.
- Doing nothing beyond the visual flash message — rejected outright: fails FR-033/FR-037 (color/visual-only
  status conveyance is explicitly disallowed).

## 9. Automated WCAG 2.1 AA scanning for SC-009

**Decision**: Add `com.microsoft.playwright:playwright:1.52.0` and `com.deque.html.axe-core:playwright:4.10.1`
as `test`-scope dependencies. A new `a11y.HomepageAccessibilityIT` (and siblings for the other screens SC-009
names: topic propose/edit forms, the new organiser settings toggles, Content Page/Content Image management,
the Info section) uses `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` (the same
pattern `ActuatorHealthIT` already established for a real running server) plus Testcontainers PostgreSQL,
launches headless Chromium via Playwright, authenticates using the same `mockOidcLogin()`-style session
seeding 002's IT suite uses (adapted to a real browser via a pre-authenticated cookie/session, not a UI login
flow — no live IdP needed), navigates to each target page, and runs Deque's `AxeBuilder.analyze()`, asserting
`violations().stream().noneMatch(v -> v.getImpact() in {"critical","serious"})`. Per FR-030's explicit scope
note, pre-existing feature-002 organiser screens (Users, Skills, Custom Fields, Groups list/detail/forms) are
**not** included in this suite.

**Rationale**: `com.deque.html.axe-core:playwright` is Deque's own first-party Java binding for axe-core
(the industry-standard automated accessibility rule engine) targeting Playwright specifically — it needs no
separately-vendored `axe.min.js` asset and stays version-pinned via Maven like every other dependency in this
project. Playwright is the natural pairing (real Chromium, real rendering/CSS/computed-contrast, unlike
`WebTestClient`) and its Java bindings require no Node.js toolchain, keeping the project's "JVM-only" posture
(Constitution III's rationale) intact even though this is a browser-driving test tool.

**Alternatives considered**:
- Selenium + a vendored `axe-core` JS bundle invoked via `executeScript` — rejected: Playwright's Java API is
  more ergonomic and Deque's first-party AxeBuilder wrapper removes the need to hand-roll the JS injection and
  result-parsing this codebase would otherwise have to maintain itself.
- A pure static-analysis approach (Jsoup-based rule checks for `alt` attributes, label associations, etc.) —
  rejected as insufficient on its own: it cannot evaluate computed contrast (FR-038) or actual keyboard focus
  order (FR-031), both of which SC-009 needs covered, and axe-core already implements those checks correctly.
- Skipping automation and relying solely on manual testing — rejected: SC-009 explicitly requires "an
  automated accessibility scan."

## 10. Revoke-removes-from-group integration (FR-007a)

**Decision**: Add one new public method to the existing `GroupService`: `findActiveGroupForParticipant(UUID
participantId)` (a thin public wrapper around the `group_members`-querying logic that already exists
privately as `findActiveGroupIdForParticipant`, extended to also fetch the `Group` row). `ParticipantService`'s
new `selfRevoke(UUID participantId)` method — after flipping `status` to `REVOKED` — calls this, and if a Group
is found, calls `GroupService.removeMember(group.getId(), participantId)` (the existing, already-tested
method that flips the `group_members.active` flag while preserving history, exactly as FR-007a requires). No
new SQL is written for this step at all; it's pure composition of two already-existing, already-tested service
methods.

**Rationale**: `GroupService.removeMember` already implements exactly the semantics FR-007a needs (deactivate
membership, keep history) — duplicating that logic inside `ParticipantService` via direct `DatabaseClient`
calls would violate the "don't add abstractions/duplicate logic beyond what's needed" guidance for no benefit.
Cross-domain composition at the service layer (one service calling another's public method) is already the
established pattern here (`TopicController` calling `GroupService` directly, per its own doc comment: "the
`group` domain depends on `topic`... not the reverse").

**Alternatives considered**: Duplicating the `UPDATE group_members SET active = false ...` statement inside
`ParticipantService` — rejected as needless duplication of `GroupService`'s existing, tested logic.

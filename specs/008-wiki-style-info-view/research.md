# Phase 0 Research: Wiki-Style Info View

The spec's Clarifications session already resolved every open product question. This phase resolves the remaining *technical* unknowns needed to implement it inside the existing codebase's conventions.

## 1. Representing the context designation

**Decision**: Replace `ContentPage.isHomepage` (boolean) with `ContentPage.context` of a new enum `ContentPageContext { NONE, HOMEPAGE, TOPIC_CREATION, USER_REGISTRATION }`, persisted as a `text` column named `context`, default `'NONE'`.

**Rationale**: This codebase already has two enums (`ParticipantStatus`, `TopicApprovalStatus`) persisted as `text` columns with zero custom converters — Spring Data R2DBC's `MappingR2dbcConverter` handles `Enum.name()` round-tripping natively. Reusing that exact pattern keeps the change idiomatic and avoids introducing a Postgres native enum type (which would need its own DDL and R2DBC codec registration). A single nullable-free `context` column with a `NONE` sentinel (rather than a nullable "which context" column) makes "no designation" an explicit, queryable value instead of a null special case, and makes exclusivity enforceable with one partial unique index.

**Alternatives considered**:
- *Three separate booleans* (`isHomepage`, `isTopicCreation`, `isRegistration`): rejected — spec FR-012a explicitly requires a single-choice control so two contexts can never be claimed at once; three independent booleans would let the database (absent extra constraints) represent an invalid combined state that the UI would then have to prevent entirely on its own.
- *Postgres native enum type*: rejected — more DDL ceremony (`CREATE TYPE`, `ALTER TYPE ... ADD VALUE` for future contexts) for no behavioural benefit over the `text`-column pattern already established twice in this codebase.

## 2. Enforcing "at most one page per context"

**Decision**: `CREATE UNIQUE INDEX content_pages_context_key ON content_pages (context) WHERE context <> 'NONE';` replacing the existing `content_pages_is_homepage_key` partial index. Application-level exclusivity (unset whichever page previously held a non-`NONE` context before saving the new designation) stays in `ContentPageService`, exactly mirroring the current `unsetPreviousHomepageIfNeeded` pattern — the index remains the concurrency-safe backstop, not the primary mechanism.

**Rationale**: Directly generalises the existing `content_pages_is_homepage_key` partial-unique-index approach (schema.sql) from one context to three, with no new enforcement idiom to design.

## 3. Merging the list view and detail view into one wiki route

**Decision**: `InfoController` keeps two routes — `GET /info` (default page) and `GET /info/{id}` (specific page) — but both render the *same* Thymeleaf template (`info/index.html`), built from a `Mono.zip` of `contentPageService.findInfoList()` (the menu, `Flux` collected to `List`) and either the first page by menu order or the requested id's rendered detail. `ContentPageService` gains a `findRenderedDefault()` (first of `findInfoList()`) and keeps `findRenderedDetail(id)`, both filtered to `context == NONE` so a designated or unknown id resolves the same way. A page that resolves to nothing (unknown, deleted, or now-designated id) still renders `info/index.html` with the menu populated and a "not found" message in the content pane and a 404 status, per FR-008.

**Rationale**: Keeps the existing controller/route shape (two `@GetMapping`s already exist) rather than collapsing to a single query-parameter route, so every page keeps its own bookmarkable, individually linkable URL (FR-006) with no routing change for existing links. Reusing `Rendering.view(...).build()` with a `HttpStatus.NOT_FOUND` status (instead of throwing `ResponseStatusException`, which would bypass the view and lose the menu) is what makes "not found, but still the wiki layout" possible — `Rendering.Builder.status(HttpStatus)` already exists elsewhere in this codebase (e.g. `Rendering.redirectTo(...).status(...)` in several controllers) for exactly this reason.

**Alternatives considered**: A single `GET /info?page={id}` route was rejected — it would require rewriting every existing `/info/{id}` link and testcase for no benefit, since Thymeleaf's `th:replace` already lets one template serve both "default" and "specific id" cases from two thin controller methods.

## 4. Empty state (User Story 4)

**Decision**: `findInfoList()` returning empty drives both `GET /info` and `GET /info/{id}` (any id, since none can exist) into an empty-state branch of `info/index.html` — a message paragraph, plus a "New page" link when `isOrganiser` (already available in every model via `CurrentUserModelAdvice`). No new controller branch is needed beyond checking `pages.isEmpty()` in the template, matching the existing `list.html`'s `th:if="${pages.isEmpty()}"` idiom.

**Rationale**: Reuses the exact empty-check idiom already in `info/list.html` rather than inventing a new one.

## 5. Current-page indication for assistive technology (FR-003)

**Decision**: The menu's active `<li>`/`<a>` gets `aria-current="page"` (a standard HTML/ARIA attribute, no library needed) alongside a CSS class for the visual indicator, set via `th:aria-current="${page.id == currentPage.id} ? 'page' : null"`-style conditional attribute.

**Rationale**: `aria-current="page"` is the standard mechanism screen readers use for "this is the current page in a set of navigation links" (native HTML/WAI-ARIA, needs no new dependency); this codebase already has `*AccessibilityIT` tests (axe-based) that would catch a missing semantic here, and the existing nav in `fragments/layout.html` does not yet need this pattern but Pico CSS's own docs use the same attribute for exactly this purpose.

## 6. Opening authoring screens in a new tab

**Decision**: Edit/New page links get `target="_blank" rel="noopener"`.

**Rationale**: `target="_blank"` is what "opens in a new browser tab" means at the HTML level; `rel="noopener"` is required so the newly-opened organiser tab cannot use `window.opener` to navigate the original wiki tab (a well-known reverse-tabnabbing risk with `target="_blank"`) — standard practice, no dependency needed.

## 7. Mandatory, validated sort index

**Decision**: `ContentPageController` parses `sort_index` strictly (reject blank or non-integer with a `ContentPageConflictException`-driven validation message re-rendering the form), instead of the current `parseIntOrZero` silent fallback. `ContentPageService.create`/`update` signatures take an already-parsed `int`; the controller does the string→int parsing and blank/format validation before calling the service, mirroring how `TopicSelfServiceController` already validates required fields at the controller boundary before invoking its service.

**Rationale**: FR-019 explicitly forbids substituting a default for a missing/non-numeric index — the current `parseIntOrZero` behaviour is exactly the fallback the spec now disallows, so it must be replaced, not extended.

## 8. Pre-filling the index on the New page form

**Decision**: `ContentPageRepository` gains `Mono<Integer> findMaxSortIndex()` (a small derived/`@Query`-backed aggregate), and `ContentPageService.nextSortIndex()` returns `findMaxSortIndex().map(max -> max + 1).defaultIfEmpty(0)`. `ContentPageController.newForm()` calls it and passes the result as `sortIndex` to the existing form template (which already renders `th:value="${sortIndex}"` on the edit path — the new-page path currently passes no `sortIndex` at all).

**Rationale**: FR-019a/FR-019b need the *next* value when pages exist and a fixed starting value (`0`, matching the existing schema seed row's `sort_index = 0`) when none exist — a single `defaultIfEmpty(0)` expresses both cases without a branch.

## 9. Deletion confirmation for a designated page

**Decision**: The delete `<form>`'s submit button in `organiser/content-pages/list.html` gets a conditional inline `onclick="return confirm('...')"` naming the context, present only `th:if="${page.context.name() != 'NONE'}"`; an undesignated page's delete button is untouched (no `onclick`, no added step), matching FR-018a exactly.

**Rationale**: This codebase has no client-side framework and no modal/dialog component; the native `confirm()` dialog is the same "are you sure" idiom already reachable with zero new dependencies, and gating it with a plain `th:if` keeps the undesignated path byte-for-byte identical to today's behaviour (the spec's explicit "no added confirmation step" requirement for that path).

## 10. Rendering designated content on the registration/topic-creation forms

**Decision**: `ContentPageService` gains `findRenderedByContext(ContentPageContext context)` (reuses the existing `render()` private helper). `RegistrationController.registerForm` and `TopicSelfServiceController.newForm` each zip in `findRenderedByContext(...)`, passing `designatedBodyHtml` (nullable) to their templates; the templates render it with `th:utext` (unwrapped, no card) directly above the `<form>` when present, matching how `home/index.html` already handles `homepageContent` being present-or-absent from `findRenderedHomepage()`. `TopicSelfServiceController.editForm`/`update` are untouched — they never populate `designatedBodyHtml`, so the include is naturally absent on the edit path (FR-015).

**Rationale**: Directly reuses the `RenderedContentPage` record and `render()` sanitization boundary already in `ContentPageService` — no new rendering path, no new sanitizer policy.

# Implementation Plan: Homepage Overview, Self-Service Registration & Topics

**Branch**: `003-homepage-overview-participant` | **Date**: 2026-08-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-homepage-overview/spec.md`

## Summary

Add a two-column authenticated homepage: the left column shows the current user's Participant status with
self-service Register/Revoke actions and a topic list (browse, propose, edit-own), the right column renders an
Organiser-designated markdown Content Page. An "Info" nav item lists further markdown Content Pages in
Organiser-defined order. Organisers gain a settings area (toggle self-registration, self-revocation,
topic-approval) and Content Page / Content Image management (upload images to a DB-backed library, embed via
markdown, edit alt text, delete only when unreferenced). Everything server-rendered with Thymeleaf/Pico CSS per
the constitution, on top of the existing WebFlux + R2DBC + OIDC stack from feature 002, extended with a
markdown-to-sanitized-HTML pipeline and a first automated-accessibility test category (SC-009).

## Technical Context

**Language/Version**: Java 25 (per `pom.xml`, unchanged from 002)

**Primary Dependencies**: Spring Boot 4.1.1 — `spring-boot-starter-webflux`, `spring-boot-starter-data-r2dbc`,
`spring-boot-starter-thymeleaf`, `spring-boot-starter-oauth2-client`, `spring-boot-starter-actuator` (all
already present). New for this feature: `org.commonmark:commonmark:0.24.0` (markdown → HTML),
`com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20240325.1` (HTML sanitization, FR-022),
`com.microsoft.playwright:playwright:1.52.0` + `com.deque.html.axe-core:playwright:4.10.1` (test scope only —
automated WCAG scan for SC-009).

**Storage**: PostgreSQL via R2DBC (`postgres:18.6-alpine`, per `docker-compose.yml`), same
`spring.sql.init.mode=always` idempotent `schema.sql` convention as 002; new tables `organiser_settings`,
`content_pages`, `content_images`; `topics` gains an `approval_status` column.

**Testing**: JUnit 5 + Mockito (unit, service-layer), `WebTestClient` against a real
`SecurityWebFilterChain` + Testcontainers PostgreSQL (integration, `*ManagementIT` / `*IT` naming, per 002's
established pattern) — plus a new category: Playwright-driven headless-Chromium + axe-core scans
(`@SpringBootTest(webEnvironment = RANDOM_PORT)`) for SC-009's automated WCAG 2.1 AA check, which
`WebTestClient` cannot perform since it never renders a real accessibility tree.

**Target Platform**: Linux container (existing devcontainer / `docker-compose.yml`), unchanged.

**Project Type**: Web — single Spring Boot WebFlux application, server-rendered Thymeleaf, no separate
frontend build (per Constitution Principle III).

**Performance Goals**: No numeric SLA in the spec; inherits the constitution's non-blocking-reactive mandate.
Scale is hackathon-event-sized (tens to low hundreds of concurrent authenticated users per SC's implicit
scope), so no special throughput engineering beyond "stay on WebFlux, no blocking calls" is warranted.

**Constraints**: WCAG 2.1 AA on all new UI (FR-030–FR-038); Content Image upload capped at 5 MB, PNG/JPEG/
GIF/WebP only (FR-029); image binaries stored in Postgres, not the filesystem (FR-024); all markdown-derived
HTML sanitized before render, no exceptions (FR-022); no client-side rendering framework (Constitution III) —
the confirmation dialog (FR-035) and any non-reload status announcement (FR-033) must be built from native
HTML (`<dialog>`) plus at most minimal vanilla JS, never a framework.

**Scale/Scope**: Single ongoing hackathon (Organiser Settings is one global row, consistent with 002's
single-event assumption); 7 user stories, FR-001–FR-038 (39 functional requirements including sub-letters).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Result |
|---|---|---|
| I. Spring Boot Native Only | New deps (commonmark, OWASP sanitizer, Playwright+axe-core) are narrow-purpose libraries, not alternative frameworks or DI containers; no `spring-webmvc` transitive dependency introduced (verify via `mvn dependency:tree` in Phase 0/1). Auto-configuration remains the primary wiring mechanism. | PASS |
| II. Reactive-First (WebFlux) | All new controllers/services return `Mono`/`Flux`; Content Image byte serving streams via R2DBC + `DataBufferUtils`, no blocking I/O. Commonmark parsing/OWASP sanitization run as in-memory CPU-bound calls (like existing JSON/HTML serialization), not blocking I/O — acceptable on Reactor threads by the same standard the codebase already applies to Thymeleaf rendering itself. | PASS |
| III. Thymeleaf SSR | All pages server-rendered. The one deliberate exception — a few lines of vanilla JS to call `<dialog>.showModal()`/`.close()` for the Revoke confirmation (FR-035) and to move focus after a redirect — is not a "client-side rendering framework" (React/Vue/Angular) and introduces no build pipeline; see research.md §8. | PASS (documented, not a violation) |
| IV. Pico CSS Styling | Continue using Pico CSS exclusively; small custom CSS only for the two-column responsive layout (FR-001a stacking order), the "Pending approval" badge (FR-012b/FR-034), and a visually-hidden utility class for the `aria-live` status region — all overrides Pico's classless defaults don't cover. | PASS |
| V. Test-First (NON-NEGOTIABLE) | Every new service/controller gets a failing unit/integration test first, following 002's `XxxServiceTest` (Mockito) / `organiser/xxx/XxxManagementIT` (`WebTestClient`) naming. The Playwright+axe-core suite is a genuinely new test *category* for this project (no browser-based test exists yet) — flagged below in Complexity Tracking, justified by SC-009's explicit requirement for an automated accessibility scan, which no existing tool in the stack can perform. | PASS (new category justified) |

No unjustified violations. One complexity addition (new browser-based test tooling) is tracked below.

**Post-Phase-1 re-check**: Phase 1 design (data-model.md, contracts/, quickstart.md) introduced no new
dependency or pattern beyond what Phase 0 research already justified — the `organisersettings`/`content`
packages, the `MarkdownRenderer` sanitization boundary, the shared layout `@ControllerAdvice`, and the
`a11y.*IT` Playwright suite all match the decisions above exactly. No table, route, or template design
required a `spring-webmvc`-pulling library, blocking I/O, a client-side framework, a non-Pico CSS framework,
or an untested code path. Gate re-confirmed: PASS, same single tracked complexity item.

## Project Structure

### Documentation (this feature)

```text
specs/003-homepage-overview/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   ├── registration-and-status.md
│   ├── organiser-settings.md
│   ├── topics-self-service-and-approval.md
│   ├── content-pages-and-info.md
│   └── content-images.md
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

Single Spring Boot module (Constitution I/II/III), same package-by-domain layout 002 established. This
feature extends existing domain packages and adds new ones for the entities it introduces; participant-facing
web routes get their own top-level packages (mirroring how `organiser.*` already isolates admin-only routes),
since `SecurityConfig`'s `/organiser/**` → `ROLE_ORGANISER` path rule must stay the sole gate for
Organiser-only pages and must NOT cover the new self-service routes.

```text
src/main/java/net/fabcelhaft/hackathonorganiser/
├── organisersettings/                  # NEW — OrganiserSettings entity, repository, service
│   ├── OrganiserSettings.java
│   ├── OrganiserSettingsRepository.java
│   └── OrganiserSettingsService.java
├── content/                             # NEW — Content Page + Content Image domain
│   ├── ContentPage.java
│   ├── ContentPageRepository.java
│   ├── ContentPageService.java
│   ├── ContentImage.java
│   ├── ContentImageRepository.java
│   ├── ContentImageService.java
│   ├── ContentImageConflictException.java   # delete-blocked-while-referenced (FR-028)
│   └── MarkdownRenderer.java             # commonmark + OWASP sanitizer + heading-level shift (research.md §1)
├── topic/                               # EXTENDED — approval_status, approve(), reassignAuthor()
│   ├── Topic.java                        # + approvalStatus field
│   ├── TopicApprovalStatus.java          # NEW enum: PENDING, APPROVED
│   └── TopicService.java                 # + propose()/participant-edit guard/approve()/reassignAuthor()
├── participant/                         # EXTENDED — self-service register/revoke, revoke-removes-from-group
│   └── ParticipantService.java           # + selfRegister()/selfRevoke() honoring OrganiserSettings + Group removal
├── group/                               # EXTENDED — expose lookup needed by participant self-revoke
│   └── GroupService.java                 # + public findActiveGroupForParticipant()
├── home/                                 # NEW — participant-facing homepage controller
│   └── HomeController.java               # GET /, POST /register, POST /revoke
├── info/                                 # NEW — participant-facing Info section controller
│   └── InfoController.java               # GET /info, GET /info/{id}
├── topic/web/ (or a top-level `topics` web package)   # NEW — participant-facing propose/edit routes
│   └── TopicSelfServiceController.java   # GET/POST /topics/new, GET/POST /topics/{id}/edit
├── organiser/
│   ├── settings/                         # NEW
│   │   └── OrganiserSettingsController.java   # GET/POST /organiser/settings
│   ├── content/                          # NEW
│   │   ├── ContentPageController.java    # /organiser/content-pages CRUD + homepage-designation + sort index
│   │   └── ContentImageController.java   # /organiser/content-images upload/alt-text-edit/delete
│   ├── topic/
│   │   └── TopicController.java          # EXTENDED — approve action, author-reassign field on edit form
│   └── (users/participants/skills/custom-fields/groups controllers unchanged)
├── web/                                   # NEW — cross-cutting web layer support
│   ├── ContentImageStreamController.java # GET /content-images/{id} — raw bytes, any authenticated user
│   └── CurrentUserModelAdvice.java       # @ControllerAdvice: injects currentUser/isOrganiser into every model (research.md §7)
└── security/ (unchanged: SecurityConfig, HackathonOidcUser, HackathonOidcUserService)

src/main/resources/
├── schema.sql            # EXTENDED — organiser_settings, content_pages, content_images, topics.approval_status
└── templates/
    ├── fragments/layout.html            # NEW — shared header/nav (Organiser link, Info link) used by every page
    ├── home/index.html                  # NEW — two-column homepage
    ├── info/list.html, info/detail.html # NEW
    ├── topics/form.html                 # NEW — participant propose/edit form
    ├── organiser/fragments/layout.html  # UNCHANGED (existing organiser-only pages keep using it, or are
    │                                       migrated to the shared fragment — decided in Phase 1/tasks)
    ├── organiser/settings/form.html     # NEW
    ├── organiser/content-pages/{list,form}.html   # NEW
    └── organiser/content-images/list.html          # NEW

src/test/java/net/fabcelhaft/hackathonorganiser/
├── organisersettings/OrganiserSettingsServiceTest.java
├── content/{ContentPageServiceTest,ContentImageServiceTest,MarkdownRendererTest}.java
├── topic/TopicServiceTest.java           # EXTENDED — approval-state cases
├── participant/ParticipantServiceTest.java   # EXTENDED — self-register/self-revoke + group-removal cases
├── home/HomeControllerIT.java
├── info/InfoManagementIT.java
├── topic/TopicSelfServiceManagementIT.java
├── organiser/settings/SettingsManagementIT.java
├── organiser/content/{ContentPageManagementIT,ContentImageManagementIT}.java
└── a11y/HomepageAccessibilityIT.java     # NEW — Playwright + axe-core, RANDOM_PORT (research.md §9)
```

**Structure Decision**: Continue the existing single-module, package-by-domain layout. New domain concepts
(`organisersettings`, `content`) get their own top-level packages, matching how `topic`/`participant`/`skill`
already isolate their entity+repository+service. New *web* packages are split by audience, not by domain:
`organiser.*` stays exactly what `SecurityConfig`'s `/organiser/**` rule protects (Organiser-only), while
`home`, `info`, and a new self-service topic package sit outside that prefix so Standard/Participant users can
reach them under plain `.anyExchange().authenticated()`. A single shared Thymeleaf layout fragment
(`templates/fragments/layout.html`) replaces having two independent nav bars, so FR-008's conditional Organiser
link and the new Info link render consistently everywhere; whether the existing `organiser/fragments/layout.html`
is deleted in favor of the shared one or kept as a thin wrapper is an implementation-level call left to
`tasks.md`.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| New test tooling: Playwright (Java) + Deque's axe-core-for-Playwright, headless Chromium, `@SpringBootTest(webEnvironment = RANDOM_PORT)` | SC-009 requires an *automated* accessibility scan reporting zero critical/serious WCAG 2.1 AA violations. `WebTestClient` (the project's existing integration-test tool) only ever exercises the reactive HTTP layer against a mock exchange — it never builds a DOM or accessibility tree, so it structurally cannot evaluate contrast, focus order, ARIA roles, or landmark structure. | Hand-rolled HTML/ARIA assertions against the raw Thymeleaf output (regex/Jsoup checks) were considered, but they only catch the small subset of WCAG rules expressible as static markup pattern-matching (e.g. "an `<img>` has an `alt`") — they cannot catch computed-contrast failures, tab-order problems, or `<dialog>` focus-trap behavior, which is exactly the class of rule SC-009 is aimed at. Manual-only testing was rejected because SC-009 explicitly says "automated". |

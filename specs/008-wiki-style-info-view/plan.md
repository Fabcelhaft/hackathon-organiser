# Implementation Plan: Wiki-Style Info View

**Branch**: `008-info-view-more` | **Date**: 2026-09-05 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/008-wiki-style-info-view/spec.md`

## Summary

Replace the two-step Info flow (list page → separate detail page) with a single wiki-style view: a left-hand menu of every undesignated Content Page plus the selected page's rendered content on the right, with organiser-only Edit/New page actions opening the existing management screens in a new tab. Generalise the existing single-purpose `is_homepage` boolean into a four-value `context` designation (`NONE`, `HOMEPAGE`, `TOPIC_CREATION`, `USER_REGISTRATION`) so a Content Page can also be pinned above the participant-facing topic-creation and registration forms, with the same one-page-per-context exclusivity the homepage already enforces.

## Technical Context

**Language/Version**: Java 25

**Primary Dependencies**: Spring Boot (WebFlux, Data R2DBC, Thymeleaf, OAuth2 Client), commonmark-java, owasp-java-html-sanitizer, Pico CSS

**Storage**: PostgreSQL via R2DBC; schema evolution through the existing idempotent `schema.sql` (`CREATE TABLE IF NOT EXISTS` / `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`) — no separate migration tool is in use

**Testing**: JUnit 5 + Mockito (unit), `WebTestClient` (integration, `*ManagementIT`), axe-based `*AccessibilityIT` for a11y regressions

**Target Platform**: Linux server (containerised Spring Boot app)

**Project Type**: Single Spring Boot web application (server-rendered Thymeleaf, no separate frontend)

**Performance Goals**: No new performance goals; page counts are hackathon-scale (tens of pages), so no pagination or search is introduced (spec Assumptions)

**Constraints**: Must preserve existing `/organiser/**` role gating, existing CSRF posture, and the existing Content Page pool/schema shape beyond the designation field; content pages remain outside the audit trail

**Scale/Scope**: Touches `content` (entity/service/repository), `info` (controller/templates), `organiser/content` (controller/templates), `home`, `participants` (registration), `topics` (self-service create form) — no new bounded context

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Spring Boot Native Only | No new frameworks/DI containers introduced; reuses existing Spring Data R2DBC repository and `@ControllerAdvice` patterns | PASS |
| II. Reactive-First (WebFlux) | All new/changed controller methods return `Mono<Rendering>`; new service methods return `Mono`/`Flux`; no blocking calls added | PASS |
| III. Thymeleaf Server-Side Rendering | Wiki view, designation control, and designated-content includes are all Thymeleaf fragments/templates; no client-side framework introduced | PASS |
| IV. Pico CSS Styling | Menu/content-pane layout uses Pico CSS's semantic grid/classless elements plus the existing `app.css` override point; no new CSS framework | PASS |
| V. Test-First Development | New/changed behaviour (wiki routing, designation exclusivity, form pre-fill, deletion confirmation) gets failing `WebTestClient` ITs and unit tests before implementation, per existing `*ManagementIT` / `*ServiceTest` conventions | PASS |

No violations — Complexity Tracking section is not needed.

**Post-Phase-1 re-check**: Data model (enum persisted as a `text` column, partial unique index) and contracts
(all routes remain `Mono<Rendering>`-returning, Thymeleaf-rendered, Pico-styled) introduce no new dependency,
blocking call, or non-reactive return type. Gate still PASSES with no changes to the table above.

## Project Structure

### Documentation (this feature)

```text
specs/008-wiki-style-info-view/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
src/main/java/net/fabcelhaft/hackathonorganiser/
├── content/
│   ├── ContentPage.java                 # boolean isHomepage -> ContentPageContext context
│   ├── ContentPageContext.java          # NEW: enum NONE, HOMEPAGE, TOPIC_CREATION, USER_REGISTRATION
│   ├── ContentPageRepository.java       # findByIsHomepageTrue -> findByContext; add findMaxSortIndex
│   ├── ContentPageService.java          # findHomepage -> findByContext(ctx); wiki list/detail; index pre-fill
│   └── ContentPageConflictException.java
├── info/
│   └── InfoController.java              # GET /info, GET /info/{id} both render the wiki template
├── organiser/content/
│   └── ContentPageController.java       # designation single-select instead of homepage checkbox; index pre-fill on /new; delete confirmation
├── home/HomeController.java             # findHomepage() call site updated to findByContext(HOMEPAGE)
├── participants/RegistrationController.java   # prepend USER_REGISTRATION-designated content
└── topics/TopicSelfServiceController.java     # prepend TOPIC_CREATION-designated content on newForm only

src/main/resources/
├── schema.sql                            # is_homepage boolean -> context text column + partial unique index
└── templates/
    ├── info/
    │   ├── index.html                    # NEW: wiki layout (menu + content pane), replaces list.html + detail.html
    │   └── (list.html, detail.html removed)
    ├── organiser/content-pages/
    │   ├── form.html                     # homepage checkbox -> context radio/select control; index pre-filled
    │   └── list.html                     # Homepage column -> Designation column
    ├── participants/register.html        # designated content included above the form
    └── topics/form.html                  # designated content included above the form, create-only (topicId == null)

src/test/java/net/fabcelhaft/hackathonorganiser/
├── content/ContentPageServiceTest.java              # NEW unit tests for context exclusivity, index pre-fill
├── info/InfoManagementIT.java                        # updated for wiki routes, empty state, not-found-in-layout
├── organiser/content/ContentPageManagementIT.java    # updated for designation control, delete confirmation
├── participants/RegistrationManagementIT.java        # updated for designated content assertion
└── topics/TopicSelfServiceManagementIT.java          # updated for designated content assertion (create vs edit)
```

**Structure Decision**: Single Spring Boot application (existing layout). No new modules/projects — this feature extends the existing `content`, `info`, `organiser.content`, `home`, `participants`, and `topics` packages in place, consistent with how the homepage designation (feature 003) was implemented.

## Complexity Tracking

*No Constitution Check violations — table intentionally omitted.*

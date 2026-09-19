# Implementation Plan: Topic Markdown Descriptions & Attachments

**Branch**: `010-topic-markdown-attachments` | **Date**: 2026-09-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/010-topic-markdown-attachments/spec.md`

## Summary

Render Topic descriptions through the existing `MarkdownRenderer` (commonmark + OWASP sanitizer) on both Topic detail views, moved out of the key/value table into a full-width section under the heading; extend that shared renderer with bare-URL autolinking and new-tab links (which deliberately also changes Content Pages); add a markdown hint fragment to both Topic forms; and introduce a new `TopicAttachment` entity (bytes in PostgreSQL `bytea`, exactly like `ContentImage`) with author/organiser upload+remove routes on the edit screens, an allowlist of document/image/ZIP types, 10 MB / 10-per-Topic limits, a visibility-gated forced-download route, and audit entries for every add/remove.

## Technical Context

**Language/Version**: Java 25

**Primary Dependencies**: Spring Boot 4.1 (WebFlux, Data R2DBC, Thymeleaf, OAuth2 Client), commonmark-java 0.24.0 (+ **new**: `commonmark-ext-autolink` 0.24.0), owasp-java-html-sanitizer 20260101.1, Pico CSS

**Storage**: PostgreSQL via R2DBC; new `topic_attachments` table (bytes as `bytea`, FK to `topics` with `ON DELETE CASCADE`) added to the idempotent `schema.sql`; no change to `topics.description`

**Testing**: JUnit 5 + Mockito (unit), `WebTestClient` (`*ManagementIT` against Testcontainers PostgreSQL, multipart via `MultipartBodyBuilder`), Playwright+axe `a11y.*IT`

**Target Platform**: Linux server (containerised Spring Boot app)

**Project Type**: Single Spring Boot web application (server-rendered Thymeleaf, no separate frontend)

**Performance Goals**: No new targets. Attachment listings must not load `bytea` payloads (metadata-only query); download route streams a single row's bytes. Hackathon scale: tens of Topics × ≤10 files × ≤10 MB.

**Constraints**: `spring.codec.max-in-memory-size` must rise from 6MB to 11MB so a 10 MB multipart part reaches service-level validation instead of failing in the codec; existing `/organiser/**` role gating, CSRF posture and the 404-vs-403 split for Topic edit routes are preserved; `topics.description` storage is untouched (FR-007)

**Scale/Scope**: Touches `content` (renderer + policy), `topic` (new attachment entity/repository/service, audit event types), `topics` + `organiser/topic` (controllers), 4 Topic templates + 1 new fragment, `schema.sql`, `application.yml`, `pom.xml`; no new bounded context

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Spring Boot Native Only | Only new dependency is a commonmark extension (`commonmark-ext-autolink`), a pure parsing library from the same artifact family already in use — no web framework, DI container or `spring-webmvc` transitive. Multipart handled by WebFlux's own `FilePart`/`DataBufferUtils`, as `ContentImageController` already does | PASS |
| II. Reactive-First (WebFlux) | All new controller methods return `Mono<Rendering>` / `Mono<ResponseEntity<byte[]>>`; new service returns `Mono`/`Flux`; attachment bytes read via `DataBufferUtils.join` (non-blocking), list query via `DatabaseClient`; no blocking I/O | PASS |
| III. Thymeleaf Server-Side Rendering | Description via `th:utext` of pre-sanitized HTML (the one existing exception, unchanged in kind); attachments sections, hint fragment and forms are plain Thymeleaf; no client-side framework | PASS |
| IV. Pico CSS Styling | Hint uses Pico's `<small>` under the field; attachment lists use plain `<table>`/`<ul>`; description section is a plain `<section>`; at most one small `app.css` override for the description block spacing | PASS |
| V. Test-First Development | Every new behaviour (autolink + target/rel, allowlist, limits, permissions, visibility-gated download, audit entries, layout change) gets failing `MarkdownRendererTest`/`TopicAttachmentServiceTest` unit tests and `TopicSelfServiceManagementIT`/`TopicManagementIT`/`InfoManagementIT` ITs before implementation | PASS |

No violations — Complexity Tracking section is not needed.

**Post-Phase-1 re-check**: Data model (one new table mirroring `content_images`, cascade FK), contracts (all routes `Mono`-returning, Thymeleaf-rendered, Pico-styled) and research decisions (commonmark extension, `HtmlPolicyBuilder` policy, `ContentDisposition` helper, query-parameter confirmation) introduce no new framework, blocking call or non-reactive return type. Gate still PASSES with no changes to the table above.

## Project Structure

### Documentation (this feature)

```text
specs/010-topic-markdown-attachments/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   └── topic-description-and-attachments.md
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
pom.xml                                   # + org.commonmark:commonmark-ext-autolink:0.24.0

src/main/java/net/fabcelhaft/hackathonorganiser/
├── content/
│   └── MarkdownRenderer.java             # + AutolinkExtension; + AttributeProvider (target=_blank on <a>);
│                                         #   Sanitizers.LINKS -> HtmlPolicyBuilder policy allowing target=_blank
│                                         #   and requiring rel="nofollow noopener noreferrer" (research §1–§3)
├── audit/
│   └── AuditEventType.java               # + ATTACHMENT_ADDED, ATTACHMENT_REMOVED (research §9)
├── topic/
│   ├── TopicAttachment.java              # NEW entity (table topic_attachments)
│   ├── TopicAttachmentRepository.java    # NEW ReactiveCrudRepository<TopicAttachment, UUID>
│   ├── TopicAttachmentService.java       # NEW: listFor (metadata only), upload (allowlist/limits), remove, findForDownload; audit
│   ├── TopicAttachmentConflictException.java  # NEW: validation failures -> form re-render with error
│   └── TopicAttachmentType.java          # NEW: extension -> allowed MIME types allowlist (research §5)
├── topics/
│   ├── TopicSelfServiceController.java   # detail: + descriptionHtml + attachments; editForm: + attachments + confirmation;
│   │                                     # + POST /topics/{id}/attachments, POST /topics/{id}/attachments/{aid}/delete
│   └── TopicAttachmentDownloadController.java  # NEW @RestController GET /topics/{id}/attachments/{aid} (visibility-gated, forced download)
└── organiser/topic/
    └── TopicController.java              # detail: + descriptionHtml + attachments; editForm: + attachments + confirmation;
                                          # + POST /organiser/topics/{id}/attachments, POST .../attachments/{aid}/delete

src/main/resources/
├── application.yml                       # spring.codec.max-in-memory-size: 6MB -> 11MB (research §6)
├── schema.sql                            # + CREATE TABLE IF NOT EXISTS topic_attachments (... ON DELETE CASCADE)
├── static/css/app.css                    # (optional) spacing for .topic-description / attachments table
└── templates/
    ├── fragments/markdown-hint.html      # NEW: <small id="description-hint"> hint fragment (FR-010, FR-011)
    ├── topics/
    │   ├── form.html                     # aria-describedby + hint; edit path: attachments section (multipart upload form,
    │   │                                 # per-row remove form, "save text first" note); new path: "add after saving" note
    │   └── detail.html                   # description section under heading (th:utext), Description row removed,
    │                                     # Attachments section between Topic Info and Joined Participants
    ├── organiser/topics/
    │   ├── form.html                     # same as topics/form.html, organiser routes
    │   └── detail.html                   # description section under heading, Description <dt>/<dd> removed, Attachments section
    └── organiser/audit/list.html         # Change column made null-safe: the new one-sided attachment
                                          # events would otherwise render "null -> file.pdf" (research §9)

src/test/java/net/fabcelhaft/hackathonorganiser/
├── content/MarkdownRendererTest.java                 # + autolink (incl. trailing punctuation), target/rel on every <a>, images kept
├── topic/TopicAttachmentServiceTest.java             # NEW unit: allowlist (type+extension), size, count, empty, audit calls
├── topics/TopicSelfServiceManagementIT.java          # + description rendering/layout, hint markup, attachment upload/remove/permissions,
│                                                     #   confirmation, download visibility (404 for invisible Pending), 403 for non-author
├── organiser/topic/TopicManagementIT.java            # + organiser detail rendering, organiser attachment routes, audit entries
├── info/InfoManagementIT.java                        # + regression: content-page links now carry target/rel; bare URL autolinked
└── a11y/TopicDetailAccessibilityIT.java              # updated for the new layout (description section + attachments table)
```

**Structure Decision**: Single Spring Boot application (existing layout). The attachment entity/service live in the existing `topic` package next to `Topic`/`TopicService` (the domain that owns them); routes live in the two controllers that already own Topic edit/detail (`topics.TopicSelfServiceController`, `organiser.topic.TopicController`) plus one new download `@RestController` in `topics`, mirroring how `web.ContentImageStreamController` is split from `organiser.content.ContentImageController`.

## Complexity Tracking

*No Constitution Check violations — table intentionally omitted.*

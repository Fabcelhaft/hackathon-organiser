<!--
SYNC IMPACT REPORT
==================
Version change: (none) → 1.0.0
Added sections: Core Principles (I–V), Technology Stack, Development Workflow, Governance
Removed sections: N/A (initial ratification)
Modified principles: N/A (initial ratification)
Deferred TODOs: None
-->

# Hackathon Organiser Constitution

## Core Principles

### I. Spring Boot Native Only
All functionality MUST be implemented using native Spring Boot and Spring Framework features.
Third-party web frameworks, alternative reactive libraries, or non-Spring dependency injection
containers MUST NOT be introduced. Spring Boot auto-configuration MUST be the primary wiring
mechanism — manual bean wiring is only permitted where auto-configuration is demonstrably
insufficient.

**Rationale**: Keeping the stack to Spring Boot native features minimises dependency surface,
leverages unified documentation, and ensures long-term maintainability by the broader Spring
ecosystem.

### II. Reactive-First (WebFlux)
All HTTP handling and I/O MUST use Spring WebFlux backed by Project Reactor. Blocking I/O
operations MUST NOT appear on Reactor threads. Return types on controllers and service methods
MUST be `Mono<T>` or `Flux<T>`. Spring MVC (`@EnableWebMvc`, `DispatcherServlet`) MUST NOT be
mixed into the same application context.

**Rationale**: WebFlux provides non-blocking throughput suitable for event-driven workloads such
as live hackathon dashboards. Mixing blocking and non-reactive code undermines this entirely.

### III. Thymeleaf Server-Side Rendering
All user-facing web pages MUST be rendered server-side using Thymeleaf templates
(`spring-boot-starter-thymeleaf` integrated with WebFlux). Client-side rendering frameworks
(React, Vue, Angular, etc.) MUST NOT be used. Dynamic content MUST be driven by Thymeleaf
expressions (`th:*`) and Spring's reactive model attributes (`ReactiveDataDriverContextVariable`
for streaming where applicable).

**Rationale**: Server-side rendering with Thymeleaf keeps the stack uniform (JVM-only), avoids a
separate frontend build pipeline, and delivers HTML directly — important for accessibility and
crawlability of public hackathon pages.

### IV. Pico CSS Styling
All UI styling MUST use Pico CSS as the sole CSS framework. Additional CSS frameworks (Bootstrap,
Tailwind, Bulma, etc.) MUST NOT be introduced. Custom CSS is permitted only for project-specific
overrides that Pico CSS's semantic defaults cannot cover. Pico CSS MUST be included via its
official distribution (CDN link or vendored static asset under `src/main/resources/static/`);
it MUST NOT be replaced by a custom build.

**Rationale**: Pico CSS provides semantic, classless styling that pairs naturally with
server-rendered Thymeleaf HTML — minimal markup, no utility-class clutter, and a consistent
visual identity with near-zero configuration.

### V. Test-First Development (NON-NEGOTIABLE)
Tests MUST be written and reviewed before implementation. The Red-Green-Refactor cycle is
strictly enforced: failing test → minimal implementation → refactor. Unit tests MUST use JUnit 5
and Mockito. Controller and integration tests MUST use `WebTestClient` (not `MockMvc`).
No feature is considered done until its tests pass and are committed.

**Rationale**: Test-first discipline surfaces design issues early, documents intent, and prevents
regression. Using `WebTestClient` keeps tests aligned with the WebFlux runtime rather than
simulating a different servlet model.

## Technology Stack

The following technology choices are locked for this project. Deviation requires a constitutional
amendment.

| Concern | Mandated Technology |
|---|---|
| Language | Java (LTS release) |
| Framework | Spring Boot (latest stable) |
| Web layer | Spring WebFlux (`spring-boot-starter-webflux`) |
| Templating | Thymeleaf (`spring-boot-starter-thymeleaf`) |
| CSS framework | Pico CSS |
| Testing (unit) | JUnit 5 + Mockito |
| Testing (integration) | `WebTestClient` |
| Build tool | Maven or Gradle (whichever the project initialises with) |

No library that pulls in `spring-webmvc` as a transitive dependency MUST be added without
explicit constitutional amendment.

## Development Workflow

1. All new features begin with a failing test commited to the repository.
2. Implementation follows only after the test is approved/reviewed.
3. Thymeleaf templates MUST be validated against the running application before a feature is
   considered complete (visual smoke-test required).
4. Reactive chains MUST be verified with `StepVerifier` for non-trivial operator combinations.
5. Static assets (including Pico CSS if vendored) MUST be placed under
   `src/main/resources/static/` and referenced via Thymeleaf's `@{/...}` syntax.
6. All HTTP endpoints MUST return `Mono` or `Flux`; `void` controller return types are forbidden.

## Governance

This constitution supersedes all other stated practices or preferences for this project.
Amendments MUST:
1. Increment the version according to semver rules documented in the Spec Kit versioning policy.
2. Include a rationale explaining why the existing principle is insufficient.
3. Be recorded in a `SYNC IMPACT REPORT` comment at the top of this file.
4. Not break existing passing tests without a migration plan.

All implementation decisions that conflict with a principle MUST be escalated as a proposed
amendment before proceeding. Complexity beyond what a principle permits MUST be justified in the
feature spec, not silently introduced.

**Version**: 1.0.0 | **Ratified**: 2026-08-22 | **Last Amended**: 2026-08-22

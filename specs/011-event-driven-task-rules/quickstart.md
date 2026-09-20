# Quickstart: Validating Event-Driven Task Rules

**Feature**: `011-event-driven-task-rules` | **Spec**: [spec.md](./spec.md)

How to prove this feature works end to end. Scenarios map to the spec's user stories so each can be
validated independently, in priority order.

## Prerequisites

- Docker running (Testcontainers starts Postgres per integration test class)
- Java 25, Maven
- No new dependency — everything needed is already in `pom.xml` (research.md, Dependencies)

## Automated checks

There is no `mvnw` wrapper in this repo — use `mvn` from the PATH.

```bash
# Unit tests — pattern scanning, path resolution, title assembly, state transitions
mvn test -Dtest='TitlePatternScannerTest,TitlePatternResolverTest,TaskServiceTest'

# Integration tests only. -Dsurefire.failIfNoSpecifiedTests=false is required: without it
# Surefire fails the build for the deliberately unmatched -Dtest pattern before Failsafe runs.
mvn verify -Dtest=NoSuchUnitTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test='TaskDestinationSenderIT,TaskManagementIT,EventDestinationManagementIT'

# Accessibility (Playwright + axe, matching the existing a11y suite)
mvn verify -Dtest=NoSuchUnitTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test='TaskListAccessibilityIT'

# Everything
mvn verify
```

Each IT class boots its own Spring context and Postgres container (~25-85 s), so Docker must be
running.

## Manual walkthrough

```bash
mvn spring-boot:run
```

Sign in as an Organiser, then:

### Story 1 — an Event creates a Task (P1)

1. **Event Destinations → New Destination.** Name it `Topic review`, set Type to **Task**.
   *Expected*: the Kafka and HTTP fields disappear, a title pattern field and a default assignee
   picker appear, and the Credential field is hidden.
2. Title pattern: `Review new topic: {{topic.name}}`. Tick **Topic proposed**. Save.
   *Expected*: the Rule appears in the list, **disabled** (FR-006), its Connection cell showing the
   pattern.
3. Enable it. Propose a Topic named `Robot Arm` as any participant.
4. **Tasks.** *Expected*: one undone Task, `Review new topic: Robot Arm`, with its created time as
   secondary text beneath the title.

### Story 2 — assign and complete (P2)

5. Pick an assignee on that row, press **Save**, reload. *Expected*: the assignee persisted.
6. Press **Done**. *Expected*: the row leaves the default view.

### Story 3 — filtering (P3)

7. Reload `/organiser/tasks` with no filter chosen. *Expected*: only undone Tasks (FR-022).
8. Switch the filter to include done Tasks. *Expected*: the completed Task appears, its completion
   time beneath its title. The Done button reads **Reopen**.
9. Press Reopen from that view. *Expected*: you stay in the done-inclusive view (FR-023), and the
   Task is undone again.

## Edge cases worth exercising by hand

These are where the feature is most likely to be subtly wrong. Each one has a requirement behind it.

| Try this | Expect | Requirement |
|---|---|---|
| Pattern `Review {{topic.name` | Save rejected, naming the unclosed wildcard; your text is not discarded | FR-007 |
| Pattern `Review {{topic..name}}` | Save rejected (doubled dot) | FR-007 |
| Pattern `Set {timeout} for {{topic.name}}` | Accepted; `{timeout}` is literal | FR-009 |
| Pattern `Contact {{user.email}}` on a participant with no email | Title reads `Contact ` — **never** `Contact null` | FR-012, research.md §3 |
| Pattern `{{topic.nope}}` only | Task created, titled after the Rule and Event Type | FR-014 |
| Pattern `{{topic.description}}` on a long description | Title cut to 500 chars ending in an ellipsis | FR-015 |
| Pattern `{{customFields.0.definition.label}}` | Resolves the first custom field's label | FR-010 |
| Propose two Topics with the same name | **Two** Tasks, identical titles, distinguishable by the time beneath each | FR-016, FR-021a |
| Save a Rule with **no** Event Types ticked, enabled | Accepted, and the list marks it inert | FR-003, FR-032a |
| Same, on an existing **Kafka** destination | Also marked inert — the marker is not Task-only | FR-032a |
| Delete a Rule with open Tasks | Confirmation states the count; undone Tasks go, done ones stay | FR-002a |
| **Disable** that Rule instead | Every Task stays; only new creation stops | FR-002a |
| Remove the Organiser role from an assignee | Their Tasks show as unassigned; nothing breaks | Edge Cases |
| Press Done twice quickly on one row | No error either time; the Task stays done | FR-027 |

## The one check that is easy to skip

**Task creation must not slow down or break the action that triggered it** (FR-018, SC-003). Point a
Task Rule at an Event Type, then confirm that proposing a Topic still succeeds and feels identical
when Task creation fails — for instance with a Rule whose pattern is valid but whose database write
errors. The triggering action must succeed, and the failure must appear only in the application log.

This holds by construction, because `EventPublisher` already dispatches every delivery as a detached
pipeline (research.md §6) — but it is the property most likely to be broken by a later refactor that
"tidies up" the subscribe call, so it is worth an explicit test rather than trust.

## References

- Pattern language, resolution table, worked examples → [contracts/title-pattern.md](./contracts/title-pattern.md)
- Routes, form fields, view changes → [contracts/organiser-ui.md](./contracts/organiser-ui.md)
- Schema and state transitions → [data-model.md](./data-model.md)
- Why each mechanism was chosen → [research.md](./research.md)

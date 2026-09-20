# Contract: Task Title Pattern Language

**Feature**: `011-event-driven-task-rules` | **Spec**: [spec.md](../spec.md) | FR-007 – FR-015

This is the contract between an Organiser writing a pattern and the system resolving it. It is the
user-facing language of the feature, so it is specified here in full rather than left to the
implementation.

---

## Grammar

```text
pattern  := ( literal | wildcard )*
wildcard := "{{" path "}}"
path     := segment ( "." segment )*
segment  := one or more characters, none of which is "." or "}"
literal  := any text, including a single "{" or "}"
```

A `{{` always opens a wildcard. Everything up to the next `}}` is its path. Anything else is literal
text and is copied through unchanged.

## Resolution

Each wildcard is resolved against the Event's **published JSON envelope** — byte for byte what a
Kafka or HTTP POST destination receives for the same Event:

```json
{ "eventType": "TOPIC_PROPOSED", "topic": { "name": "Robot Arm", ... } }
```

`eventType` is addressable like any other field, so `{{eventType}}` is valid.

Walking a path, one segment at a time:

| Current node | Segment | Result |
|---|---|---|
| object | any | the field of that name, or *missing* |
| array | all digits | that position, counting from zero, or *missing* if out of range |
| array | not all digits | *missing* |
| anything else | any | *missing* |

## What gets written

| Resolved node | Written as | Example |
|---|---|---|
| string | the text, unchanged | `Robot Arm` |
| number | as written in the JSON | `42` |
| boolean | `true` / `false` | `true` |
| timestamp (a string in the JSON) | its ISO-8601 text, unconverted | `2026-09-19T14:02:31Z` |
| identifier (a string in the JSON) | in full | `0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b` |
| JSON null | *nothing* | |
| object or array | *nothing* | |
| missing | *nothing* | |

The last three rows are **indistinguishable in the output** (FR-012). No value is reformatted,
localised, or timezone-converted (FR-011a).

> **Implementation note** — Jackson's `JsonNode.isValueNode()` returns `true` for `NullNode`, and
> `NullNode.asText()` returns the string `"null"`. A JSON null must be excluded explicitly
> (`isValueNode() && !isNull()`), or the word `null` appears in titles, which this contract forbids.
> See research.md §3.

## Assembly order

1. Replace every wildcard with its resolved text.
2. Trim leading and trailing whitespace.
3. If the result is empty, substitute the fallback: the Rule's name and the Event Type (FR-014).
4. If longer than 500 characters, cut to 500 including a trailing ellipsis (FR-015).

## Validation, at save time

A pattern is **rejected** (FR-007) when it is empty or blank, or when it contains:

| Problem | Example |
|---|---|
| unclosed wildcard | `Review {{topic.name` |
| empty path | `Review {{}}` |
| leading empty segment | `Review {{.name}}` |
| trailing empty segment | `Review {{topic.}}` |
| doubled dot | `Review {{topic..name}}` |

A pattern is **accepted** even when a path does not match anything in any Event — paths are not
checked against the Event catalog. This is deliberate: FR-012 makes an unmatched wildcard resolve to
nothing, which is what lets one Rule serve several Event Types whose data differs.

Single braces are never rejected: `Set {timeout} for {{topic.name}}` is valid, and the first pair is
literal.

## Worked examples

Against a `TOPIC_PROPOSED` Event for a topic named "Robot Arm" with no description:

| Pattern | Title |
|---|---|
| `Review new topic: {{topic.name}}` | `Review new topic: Robot Arm` |
| `{{eventType}} — {{topic.name}}` | `TOPIC_PROPOSED — Robot Arm` |
| `Review: {{topic.name}} ({{topic.description}})` | `Review: Robot Arm ()` |
| `{{topic.nonexistent}}` | *falls back to the Rule name and Event Type* |
| `Approved: {{topic.approvalStatus}}` | `Approved: PENDING` |

Against a `PARTICIPANT_REGISTERED` Event:

| Pattern | Title |
|---|---|
| `Welcome {{user.displayName}}` | `Welcome Alex Chen` |
| `Contact {{user.email}}` | `Contact alex@example.com`, or `Contact ` when no email is stored |
| `Check {{customFields.0.definition.label}}` | `Check Dietary requirements` |
| `Organiser? {{user.organiser}}` | `Organiser? false` |

The third row is the case FR-010's numeric segment exists for.

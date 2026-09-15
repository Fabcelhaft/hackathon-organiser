# Quickstart: Validate the Event Notification System

Prerequisites: the app running locally against its usual Postgres dependency (per feature 001's
setup), signed in as a user with the Organiser role.

## 1. Stand up something to receive Events

Pick one (or both) transports to validate:

- **HTTP POST**: run a local request-capturing endpoint, e.g. `python3 -m http.server 8089` is not
  enough (it doesn't show POST bodies) — instead use a one-liner Node/`ncat`, or simplest: a public
  request bin such as `https://webhook.site` for a quick manual check, capturing the JSON body it
  receives.
- **Kafka**: run a local single-broker Kafka (e.g. `docker run -p 9092:9092 apache/kafka` or the
  project's Testcontainers Kafka module if validating from a test, per research.md §8), and a
  console consumer: `kafka-console-consumer --bootstrap-server localhost:9092 --topic events
  --from-beginning`.

## 2. Create an Event Destination (Story 1 & 2 — FR-001–FR-009)

1. Sign in as an Organiser, open **Organiser → Event Destinations**, and create a new Destination:
   - HTTP: type `HTTP POST`, URL = your capture endpoint.
   - Kafka: type `Kafka`, bootstrap servers = `localhost:9092`, topic = `events`.
2. Select at least `PARTICIPANT_JOINED_TOPIC` and `GROUP_FORMED` in its Event Type selection.
3. Save — confirm it appears in the Destination list, **disabled** by default (FR-006).
4. Enable it.

**Expected**: the Destination list shows it as enabled, with those two Event Types listed
(FR-016).

## 3. Trigger a matching occurrence (Story 3 — FR-010–FR-012)

1. As a Participant (a second, non-Organiser account), join an open Topic that has no Group yet.
2. Watch your capture endpoint / Kafka consumer.

**Expected**: two separate JSON messages arrive — one `PARTICIPANT_JOINED_TOPIC` (per
`contracts/event-payloads.md` #7, including the joining Participant's `user` and `customFields` —
FR-010c, FR-010d) and one `GROUP_FORMED` (#11) — per FR-010a. The join itself
completes for the joining Participant immediately, with no visible delay, even if the Destination
were slow or unreachable (FR-020a-1) — to observe this concretely, point the Destination at an
unreachable URL/broker and confirm the join still succeeds instantly while the delivery attempt
retries and eventually fails silently into the application log (FR-020a, FR-020b).

## 4. Confirm subscription filtering (Story 3 — FR-011, FR-003 acceptance scenario 3)

1. Create a second Destination subscribed only to `TOPIC_APPROVED`.
2. Repeat step 3's join.

**Expected**: the first Destination receives its two messages again; the second Destination
receives nothing, since a join does not match its subscription.

## 5. Manage an existing Destination (Story 4 — FR-013–FR-018)

1. Edit the first Destination's URL/topic and save — confirm a subsequent join is delivered to the
   *new* target, not the old one.
2. Disable it — confirm a subsequent join produces no delivery, while the Destination's
   configuration is still visible (not cleared) when you reopen it.
3. Attempt to save it from a second browser tab after changing it in the first — confirm the
   second save is rejected as a conflict (FR-018).
4. Delete a Destination — confirm it disappears from the list and a subsequent join is not
   delivered to it.

## 6. Compliance Ruleset bulk fan-out (research.md §5 — FR-010b)

1. Ensure at least one Group exists that is currently `NOT_COMPLIANT` (e.g. below the configured
   minimum member count).
2. Subscribe a Destination to `GROUP_COMPLIANCE_CHANGED`.
3. As an Organiser, lower the Minimum Group Members setting so that Group now satisfies it.

**Expected**: exactly one `GROUP_COMPLIANCE_CHANGED` Event arrives for that Group (payload #13),
reflecting its new `COMPLIANT` status; Groups whose status was unaffected by the change produce no
Event.

## 7. Access control (FR-017)

Sign in as a non-Organiser user and attempt to open `/organiser/event-destinations` directly.

**Expected**: denied, consistent with every other `/organiser/**` route (`SecurityConfig`).

## 8. Accessibility spot-check (FR-021–FR-025)

Run this project's existing Playwright + axe automated scan (per features 003/005's precedent)
against the Destination list and create/edit form; confirm zero critical/serious violations
(SC-007).

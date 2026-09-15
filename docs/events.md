# Event Notification System — Event Catalog

This document catalogs every Event Type an Organiser can subscribe an Event Destination to
(spec.md FR-007, FR-020c), with its JSON payload shape and a worked example. It is the source an
Organiser or integrator reads to know what each subscribed Event Type actually delivers.

Every Event is delivered as a JSON envelope:

```json
{
  "eventType": "<ONE_OF_THE_13_NAMES_BELOW>",
  "...": "one or more entity objects, keyed as documented per event"
}
```

See [`specs/007-event-system-events/contracts/delivery-transport.md`](../specs/007-event-system-events/contracts/delivery-transport.md)
for the HTTP POST / Kafka wire contract (headers, retry policy, key/topic conventions), and
[`specs/007-event-system-events/data-model.md`](../specs/007-event-system-events/data-model.md)
for the full field list of each entity (`topic`, `participant`, `user`, `group`) referenced below.

This catalog is fixed (spec.md Assumptions) — an Organiser cannot add their own Event Types.

## Catalog

| # | Event Type | Fires when |
|---|---|---|
| 1 | [`PARTICIPANT_REGISTERED`](#1-participant_registered) | A Participant is registered (self-service or Organiser-driven), newly created or reactivated |
| 2 | [`PARTICIPANT_REVOKED`](#2-participant_revoked) | A Participant's status changes to `REVOKED` |
| 3 | [`PARTICIPANT_NOT_PARTICIPATED`](#3-participant_not_participated) | An Organiser sets a Participant's status to `NOT_PARTICIPATED` |
| 4 | [`USER_CREATED`](#4-user_created) | A person authenticates for the first time (new `User` row) |
| 5 | [`TOPIC_PROPOSED`](#5-topic_proposed) | A Participant proposes a new Topic |
| 6 | [`TOPIC_APPROVED`](#6-topic_approved) | An Organiser moves a Topic from `PENDING` to `APPROVED` |
| 7 | [`PARTICIPANT_JOINED_TOPIC`](#7-participant_joined_topic) | A Participant becomes a member of a Topic's Group |
| 8 | [`PARTICIPANT_LEFT_TOPIC`](#8-participant_left_topic) | A Participant stops being a member of a Topic's Group |
| 9 | [`ORGANISER_ROLE_ADDED`](#9-organiser_role_added) | A User is granted the Organiser privilege |
| 10 | [`ORGANISER_ROLE_REMOVED`](#10-organiser_role_removed) | A User's Organiser privilege is revoked |
| 11 | [`GROUP_FORMED`](#11-group_formed) | A Topic's Group is created by its first joining Participant |
| 12 | [`GROUP_DISBANDED`](#12-group_disbanded) | A Topic's Group is disbanded, however it happened |
| 13 | [`GROUP_COMPLIANCE_CHANGED`](#13-group_compliance_changed) | A Group's evaluated Compliance status changes, or an Organiser sets/clears its override |

---

Every Event Type whose payload includes `participant` also includes `user` (the same User
`participant.userId` references) and `customFields` (that Participant's currently-enabled Custom
Field definitions and answers, blank/absent where unanswered) — FR-010c, FR-010d.

## 1. `PARTICIPANT_REGISTERED`

```json
{
  "eventType": "PARTICIPANT_REGISTERED",
  "participant": { "id": "uuid", "userId": "uuid", "status": "ACTIVE",
                    "createdAt": "instant", "updatedAt": "instant" },
  "user": { "id": "uuid", "displayName": "string", "email": "string", "organiser": false,
            "createdAt": "instant", "updatedAt": "instant" },
  "customFields": [
    { "definition": { "id": "uuid", "label": "string", "fieldType": "FREE_TEXT|MULTI_SELECT",
        "required": false, "public": false, "overview": false },
      "options": [ { "id": "uuid", "label": "string" } ],
      "freeTextValue": "string|null", "selectedOptionIds": ["uuid"] }
  ]
}
```

```json
{
  "eventType": "PARTICIPANT_REGISTERED",
  "participant": {
    "id": "0191f7b2-1a2b-7c3d-8e4f-abcdef123456",
    "userId": "0191f7b2-0000-7c3d-8e4f-abcdef654321",
    "status": "ACTIVE",
    "createdAt": "2026-09-03T09:15:00Z",
    "updatedAt": "2026-09-03T09:15:00Z"
  },
  "user": {
    "id": "0191f7b2-0000-7c3d-8e4f-abcdef654321",
    "displayName": "Jane Doe",
    "email": "jane.doe@example.com",
    "organiser": false,
    "createdAt": "2026-09-03T09:14:50Z",
    "updatedAt": "2026-09-03T09:14:50Z"
  },
  "customFields": [
    {
      "definition": {
        "id": "0191f7b5-cccc-7c3d-8e4f-abcdef123456",
        "label": "T-shirt size",
        "fieldType": "FREE_TEXT",
        "required": true,
        "public": false,
        "overview": false
      },
      "options": [],
      "freeTextValue": "M",
      "selectedOptionIds": []
    }
  ]
}
```

## 2. `PARTICIPANT_REVOKED`

Same shape as #1 (`participant` + `user` + `customFields`), with `"status": "REVOKED"` and the
corresponding `updatedAt`.

## 3. `PARTICIPANT_NOT_PARTICIPATED`

Same shape as #1 (`participant` + `user` + `customFields`), with `"status": "NOT_PARTICIPATED"`
and the corresponding `updatedAt`.

## 4. `USER_CREATED`

```json
{
  "eventType": "USER_CREATED",
  "user": { "id": "uuid", "displayName": "string", "email": "string", "organiser": false,
            "createdAt": "instant", "updatedAt": "instant" }
}
```

`oidcSubject` is excluded — it is an internal IdP correlation key with no meaning to an external
consumer.

```json
{
  "eventType": "USER_CREATED",
  "user": {
    "id": "0191f7b2-0000-7c3d-8e4f-abcdef654321",
    "displayName": "Jane Doe",
    "email": "jane.doe@example.com",
    "organiser": false,
    "createdAt": "2026-09-03T09:14:50Z",
    "updatedAt": "2026-09-03T09:14:50Z"
  }
}
```

## 5. `TOPIC_PROPOSED`

Fires regardless of the resulting `approvalStatus` (`PENDING` when approval is required,
`APPROVED` when it is not).

```json
{
  "eventType": "TOPIC_PROPOSED",
  "topic": { "id": "uuid", "name": "string", "description": "string", "createdByUserId": "uuid",
             "approvalStatus": "PENDING|APPROVED", "createdAt": "instant", "updatedAt": "instant" }
}
```

```json
{
  "eventType": "TOPIC_PROPOSED",
  "topic": {
    "id": "0191f7b3-aaaa-7c3d-8e4f-abcdef123456",
    "name": "Realtime hackathon dashboard",
    "description": "A live view of team formation and topic compliance.",
    "createdByUserId": "0191f7b2-0000-7c3d-8e4f-abcdef654321",
    "approvalStatus": "PENDING",
    "createdAt": "2026-09-03T09:20:00Z",
    "updatedAt": "2026-09-03T09:20:00Z"
  }
}
```

## 6. `TOPIC_APPROVED`

Fires only from an Organiser's explicit approve action — not for a Topic created directly as
`APPROVED` (see `TOPIC_PROPOSED`).

```json
{
  "eventType": "TOPIC_APPROVED",
  "topic": { "id": "uuid", "name": "string", "description": "string", "createdByUserId": "uuid",
             "approvalStatus": "APPROVED", "createdAt": "instant", "updatedAt": "instant" }
}
```

```json
{
  "eventType": "TOPIC_APPROVED",
  "topic": {
    "id": "0191f7b3-aaaa-7c3d-8e4f-abcdef123456",
    "name": "Realtime hackathon dashboard",
    "description": "A live view of team formation and topic compliance.",
    "createdByUserId": "0191f7b2-0000-7c3d-8e4f-abcdef654321",
    "approvalStatus": "APPROVED",
    "createdAt": "2026-09-03T09:20:00Z",
    "updatedAt": "2026-09-03T09:45:00Z"
  }
}
```

## 7. `PARTICIPANT_JOINED_TOPIC`

Fires for a self-service join or an Organiser directly adding a member. Fires alongside
`GROUP_FORMED` (a separate Event) when the join is the one that creates the Group.

```json
{
  "eventType": "PARTICIPANT_JOINED_TOPIC",
  "topic": { "...": "as above" },
  "participant": { "id": "uuid", "userId": "uuid", "status": "ACTIVE|NOT_PARTICIPATED|REVOKED",
                    "createdAt": "instant", "updatedAt": "instant" },
  "user": { "...": "as in PARTICIPANT_REGISTERED above" },
  "customFields": [ { "...": "as in PARTICIPANT_REGISTERED above" } ]
}
```

```json
{
  "eventType": "PARTICIPANT_JOINED_TOPIC",
  "topic": {
    "id": "0191f7b3-aaaa-7c3d-8e4f-abcdef123456",
    "name": "Realtime hackathon dashboard",
    "description": "A live view of team formation and topic compliance.",
    "createdByUserId": "0191f7b2-0000-7c3d-8e4f-abcdef654321",
    "approvalStatus": "APPROVED",
    "createdAt": "2026-09-03T09:20:00Z",
    "updatedAt": "2026-09-03T09:45:00Z"
  },
  "participant": {
    "id": "0191f7b2-1a2b-7c3d-8e4f-abcdef123456",
    "userId": "0191f7b2-0000-7c3d-8e4f-abcdef654321",
    "status": "ACTIVE",
    "createdAt": "2026-09-03T09:15:00Z",
    "updatedAt": "2026-09-03T09:15:00Z"
  },
  "user": {
    "id": "0191f7b2-0000-7c3d-8e4f-abcdef654321",
    "displayName": "Jane Doe",
    "email": "jane.doe@example.com",
    "organiser": false,
    "createdAt": "2026-09-03T09:14:50Z",
    "updatedAt": "2026-09-03T09:14:50Z"
  },
  "customFields": [
    {
      "definition": {
        "id": "0191f7b5-cccc-7c3d-8e4f-abcdef123456",
        "label": "T-shirt size",
        "fieldType": "FREE_TEXT",
        "required": true,
        "public": false,
        "overview": false
      },
      "options": [],
      "freeTextValue": "M",
      "selectedOptionIds": []
    }
  ]
}
```

## 8. `PARTICIPANT_LEFT_TOPIC`

Same shape as `PARTICIPANT_JOINED_TOPIC` — fires for a self-service leave or an Organiser directly
removing a member, describing the Participant who left.

## 9. `ORGANISER_ROLE_ADDED`

Fires only when the Organiser privilege actually flips to granted (a no-op re-grant does not
re-fire it).

```json
{
  "eventType": "ORGANISER_ROLE_ADDED",
  "user": { "id": "uuid", "displayName": "string", "email": "string", "organiser": true,
            "createdAt": "instant", "updatedAt": "instant" }
}
```

```json
{
  "eventType": "ORGANISER_ROLE_ADDED",
  "user": {
    "id": "0191f7b2-0000-7c3d-8e4f-abcdef654321",
    "displayName": "Jane Doe",
    "email": "jane.doe@example.com",
    "organiser": true,
    "createdAt": "2026-09-03T09:14:50Z",
    "updatedAt": "2026-09-03T12:00:00Z"
  }
}
```

## 10. `ORGANISER_ROLE_REMOVED`

Same shape as `ORGANISER_ROLE_ADDED`, with `"organiser": false` and the corresponding
`updatedAt` — fires only when the privilege actually flips to revoked.

## 11. `GROUP_FORMED`

Fires once, after a new Group (and any initial members) exists for a Topic.

```json
{
  "eventType": "GROUP_FORMED",
  "group": { "id": "uuid", "topicId": "uuid", "status": "ACTIVE|DISBANDED",
             "complianceOverride": false,
             "complianceStatus": "COMPLIANT|NOT_COMPLIANT|COMPLIANT_OVERRIDE|null",
             "createdAt": "instant", "updatedAt": "instant" },
  "topic": { "...": "as in TOPIC_PROPOSED" }
}
```

```json
{
  "eventType": "GROUP_FORMED",
  "group": {
    "id": "0191f7b4-bbbb-7c3d-8e4f-abcdef123456",
    "topicId": "0191f7b3-aaaa-7c3d-8e4f-abcdef123456",
    "status": "ACTIVE",
    "complianceOverride": false,
    "complianceStatus": "NOT_COMPLIANT",
    "createdAt": "2026-09-03T09:50:00Z",
    "updatedAt": "2026-09-03T09:50:00Z"
  },
  "topic": {
    "id": "0191f7b3-aaaa-7c3d-8e4f-abcdef123456",
    "name": "Realtime hackathon dashboard",
    "description": "A live view of team formation and topic compliance.",
    "createdByUserId": "0191f7b2-0000-7c3d-8e4f-abcdef654321",
    "approvalStatus": "APPROVED",
    "createdAt": "2026-09-03T09:20:00Z",
    "updatedAt": "2026-09-03T09:45:00Z"
  }
}
```

## 12. `GROUP_DISBANDED`

Same shape as `GROUP_FORMED`, with `"status": "DISBANDED"` and `"complianceStatus": null`
(compliance no longer applies once a Group is disbanded). Fires for an Organiser's direct disband
action and for the automatic last-member-leaves disbandment alike.

## 13. `GROUP_COMPLIANCE_CHANGED`

Same shape as `GROUP_FORMED`, with `complianceStatus` set to the Group's newly evaluated value.
Fires from a single Group's own action (a join/leave that flips its status, or an Organiser
setting/clearing its compliance override) **and**, per Group, whenever an Organiser changes the
instance-wide Compliance Ruleset (Maximum/Minimum Group Members, or a Custom Field diversity
requirement) in a way that flips that Group's evaluated status.

# Event Payload Contracts

The full catalog of 13 Event Types (spec.md FR-007), each with its JSON envelope shape and a
worked example. This file is the source of truth for `docs/events.md` — the single consolidated
Markdown document FR-020c requires, covering every Event Type's shape + example, kept in step with
this catalog if it ever changes.

Every envelope's `eventType` field is the enum name (`data-model.md` "Event Type"). Entity-shaped
values (`topic`, `participant`, `user`, `group`, `customFields`) follow `data-model.md` "Event
payload shapes" exactly. Every Event Type whose payload includes `participant` also includes `user`
(the same User `participant.userId` references) and `customFields` (that Participant's
currently-enabled Custom Field definitions and answers) — FR-010c, FR-010d.

## 1. PARTICIPANT_REGISTERED

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
    },
    {
      "definition": {
        "id": "0191f7b5-dddd-7c3d-8e4f-abcdef123456",
        "label": "Dietary preferences",
        "fieldType": "MULTI_SELECT",
        "required": false,
        "public": false,
        "overview": true
      },
      "options": [
        { "id": "0191f7b5-eeee-7c3d-8e4f-abcdef123456", "label": "Vegetarian" },
        { "id": "0191f7b5-ffff-7c3d-8e4f-abcdef123456", "label": "Vegan" }
      ],
      "freeTextValue": null,
      "selectedOptionIds": ["0191f7b5-eeee-7c3d-8e4f-abcdef123456"]
    }
  ]
}
```

## 2. PARTICIPANT_REVOKED

Same shape as #1 (`participant` + `user` + `customFields`), with `"status": "REVOKED"` and the
corresponding `updatedAt`.

## 3. PARTICIPANT_NOT_PARTICIPATED

Same shape as #1 (`participant` + `user` + `customFields`), with `"status": "NOT_PARTICIPATED"`
and the corresponding `updatedAt`.

## 4. USER_CREATED

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

## 5. TOPIC_PROPOSED

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

## 6. TOPIC_APPROVED

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

## 7. PARTICIPANT_JOINED_TOPIC

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

## 8. PARTICIPANT_LEFT_TOPIC

Same shape as #7 (`topic` + `participant` + `user` + `customFields`), describing the Participant
that just left.

## 9. ORGANISER_ROLE_ADDED

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

## 10. ORGANISER_ROLE_REMOVED

Same shape as #9, with `"organiser": false` and the corresponding `updatedAt`.

## 11. GROUP_FORMED

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

## 12. GROUP_DISBANDED

Same shape as #11, with `"status": "DISBANDED"` and `complianceStatus` reflecting the Group's
state at the moment of disbandment.

## 13. GROUP_COMPLIANCE_CHANGED

Same shape as #11, with `complianceStatus` set to the Group's newly evaluated value
(`COMPLIANT` / `NOT_COMPLIANT` / `COMPLIANT_OVERRIDE`) — fired both from a single Group's own
action (join/leave/override) and, per Group, from an instance-wide Compliance Ruleset change
(data-model.md; research.md §5).

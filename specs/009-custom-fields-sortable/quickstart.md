# Quickstart: Validating Sortable Custom Fields

Prerequisites: same local setup as 002–006 — `docker-compose up -d` (Postgres), `mvn spring-boot:run`, an OIDC
login per the devcontainer setup. Two sessions are useful (one Organiser, one Participant); a single Organiser
session that is also a registered Participant works too. Contract: [custom-field-ordering.md](./contracts/custom-field-ordering.md).
Data shape: [data-model.md](./data-model.md).

## 0. Automated checks

```bash
mvn -B verify
```

Expected: green. The feature's own tests live in `CustomFieldServiceTest`, `CustomFieldManagementIT`,
`ParticipantServiceTest`, `ParticipantsDirectoryManagementIT`, `ProfileManagementIT`, and
`ComplianceManagementIT` (research.md §6).

## 1. Fixture: four fields with mixed indices (used by every step below)

As the Organiser, open **Organiser → Custom Fields → New Custom Field** and create, **in this creation order**
(so alphabetical and index order both differ from creation order):

| Label | Type | Sort index | Public | Overview |
|---|---|---|---|---|
| Omega | Free text | `3` | ✓ | ✓ |
| Mid | Free text | *(leave 0)* | ✓ | ✓ |
| Zeta | Free text | `-1` | ✓ | ✓ |
| Alpha | Free text | *(clear the input — submit empty)* | ✓ | ✓ |

**Expected**: each save redirects to the list (303). The **New** form pre-fills `0` (User Story 1, scenario 1).
"Alpha", submitted with an empty index, shows `0` in the list (Edge Cases: empty → 0).

Then make the seeded **Country** field visible everywhere the later steps expect it: it is seeded disabled,
non-public and non-overview. On the list click **Enable** on the Country row, then **Edit** it and tick
**Public** and **Overview**, leaving Sort index at `0`. (Without this, Country appears only in the management
list and the compliance dropdown, and steps 5 and 6.1 will not show it.)

## 2. Organiser list shows index and order (User Story 3, scenario 1; FR-010)

Stay on **Organiser → Custom Fields**.

**Expected**: rows in the order **Zeta (-1), Alpha (0), Country (0), Mid (0), Omega (3)** — the seeded Country
field sits alphabetically inside the 0 group between Alpha and Mid — and a **Sort index** column shows each
value. Pre-existing fields from before this feature all show `0` (User Story 1, scenario 5).

## 3. Invalid input is rejected (User Story 1, scenario 4; FR-005)

Open **Edit** on "Mid", type `abc` into Sort index (or use browser dev tools to bypass the number input and
submit `1.5`), save.

**Expected**: the form re-renders (HTTP 200) with the message "Sort index must be a whole number"; reopening the
list still shows Mid at `0`. Repeat with `99999999999` (out of 32-bit range) — same result.

## 4. Index is editable on locked fields and on Country (FR-006)

1. As a Participant, fill a value into "Mid" via **Edit profile** and save. As the Organiser, open **Edit** on
   "Mid": the Type control is now disabled (existing lock). Set Sort index to `-2`, save.
2. Open **Edit** on "Country", set Sort index to `5`, save.

**Expected**: both saves succeed (303); the list now reads **Mid (-2), Zeta (-1), Alpha (0), Omega (3),
Country (5)** (User Story 3, scenario 4; FR-011).

## 5. Participant-facing views follow the same order (User Story 2)

Reset "Mid" to `0` and "Country" to `0` first, so the fixture order is Zeta, Alpha, Country, Mid, Omega. Country
must still be enabled, public and overview from step 1 — it only appears on forms while enabled, and only in the
directory/detail views while overview/public. Then, as a Participant:

1. **Edit profile** (`/profile/edit`, or `/register` for a not-yet-registered user).
   **Expected**: inputs labelled Zeta, Alpha, Country, Mid, Omega, top to bottom (scenario 1).
2. **Participants** directory (`/participants`).
   **Expected**: header columns **Name, Zeta, Alpha, Country, Mid, Omega**; every row's cells line up under
   those headers (scenario 3).
3. Open any Participant from the directory.
   **Expected**: the public fields appear in the order Zeta, Alpha, Country, Mid, Omega (scenario 2).

## 6. Organiser views match (User Story 3, scenarios 2–3)

As the Organiser:

1. **Organiser → Participants → (any participant)**. **Expected**: Custom Field values in the order Zeta, Alpha,
   Country, Mid, Omega.
2. **Organiser → Compliance**, open the field dropdown of the "add requirement" form. **Expected**: options
   listed Zeta, Alpha, Country, Mid, Omega (fields already used by a rule are absent, as today).

## 7. Case-insensitive ties (User Story 2, scenario 4)

Create two more free-text fields at index `0`: "apple" then "Banana" (lower-case a, capital B).

**Expected**: in every view above, "apple" precedes "Banana" and both sit inside the 0 group alphabetically
(…Alpha, apple, Banana, Country, Mid…).

## 8. Identical labels tie-break (Clarifications; FR-012)

Create two free-text fields both labelled "Twin" at index `0`, a few seconds apart. Give the first one a
distinguishing Required flag so you can tell them apart in the list.

**Expected**: the one created first is listed first, in every view, across repeated reloads.

## 9. Untouched installations keep alphabetical order (SC-003)

On a database where no index was ever set (or after resetting every fixture field to `0`): all views show
fields purely alphabetically — the pre-feature behaviour.

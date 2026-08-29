# Quickstart: Validating Participant Registration Form, Profile Fields & Directory

Prerequisites: same local setup as 002/003 — `docker-compose up -d` (Postgres), `mvn spring-boot:run`, an OIDC
login available per the existing devcontainer setup. Two logged-in sessions are needed for several scenarios
below (one Organiser, one or more plain/Participant users) — use two browser profiles or one browser + one
incognito window against the same running instance.

## 1. Configure fields and settings as an Organiser

1. Log in as an Organiser, go to **Organiser → Custom Fields**.
2. Create a `SINGLE_SELECT` field (e.g. "T-shirt size", options S/M/L/XL), mark it **Overview**.
3. Enable the built-in **Country** field; mark it **Public**.
4. Go to **Organiser → Settings**: set **Maximum registrations** to `2`, leave self-edit and skill-visibility
   at their defaults, set **Participants directory visible to** = "All authenticated users".

**Expected**: Custom Fields list shows the new field plus Country, each with correct Public/Overview flags
(FR-016, contracts/custom-fields-and-country.md).

## 2. Register through the form (User Story 1)

1. Log in as a plain user with no Participant record. From the homepage, click **Register**.
2. Confirm you land on `GET /register`, see "T-shirt size" and "Country" alongside the Skill catalog as
   ordinary fields (no "Custom Field" label anywhere, FR-002a), and that any required fields are visually
   marked.
3. Submit without picking a T-shirt size if it was marked required.

**Expected**: submission rejected, the missing field identified, no Participant record created (FR-003;
`spec.md` Acceptance Scenario 1.2).

4. Fill in every required field, select a Skill or two (or none — optional, FR-004), pick a Country via the
   searchable combobox, and submit.

**Expected**: redirected home with an explicit "Registration successful" confirmation (FR-033); the
Participant record now carries exactly what was submitted (SC-001).

## 3. Capacity cap (User Story 2)

1. Register a second distinct user the same way (bringing the `ACTIVE` count to 2, the configured max).
2. As a third user, open the homepage.

**Expected**: "Maximum registrations reached" shown before the Register link leads anywhere (FR-010).

3. As one of the two registered Participants, click **Revoke Registration** and confirm.
4. As the third user, retry registration.

**Expected**: now accepted — the count dropped below the max (FR-008, FR-009; `spec.md` Acceptance
Scenario 2.2).

*(Concurrency edge case — exercised by the automated integration test, not manually: two simultaneous `POST
/register` submissions for the last slot must result in exactly one success, the other rejected with the
same capacity message — see `contracts/registration-and-self-edit.md`'s `POST /register` 200-capacity-message
case and research.md §4.)*

## 4. Self-edit (User Story 4)

1. As a registered Participant, open `GET /profile` — confirm current values render read-only, each labeled
   "visible to others" or "private" (FR-020).
2. If **Edit** is visible (self-edit enabled), open it, change the T-shirt size, save.

**Expected**: explicit save confirmation (FR-034), and the new value shows on the next `GET /profile` load
(SC-005).

3. As the Organiser, disable self-edit in Settings.
4. As the same Participant, reload `GET /profile`.

**Expected**: no Edit action is present (FR-023); navigating directly to `GET /profile/edit` redirects back
to `/profile` rather than showing a form.

## 5. Directory & visibility (User Story 5, 6)

1. As any authenticated user (directory audience is "All authenticated users" from step 1), open the
   **Participants** nav item.

**Expected**: table lists registered Participants alphabetically by name, with a "T-shirt size" column (it's
marked Overview) but **no** Skills column (FR-027).

2. Open another Participant's detail view.

**Expected**: Country (Public) is shown; T-shirt size (Overview only, not Public) is **not** shown on this
non-owning view even though it's a table column (FR-017); Skills are shown only if the Organiser has enabled
skill visibility (FR-018, FR-019).

3. As the Organiser, open the same Participant's detail view.

**Expected**: everything is visible, regardless of flags (FR-030).

4. As the Organiser, change the directory audience to "Organisers only".
5. As a plain Participant with the directory page already open in another tab, reload it (or navigate to
   `GET /participants` fresh).

**Expected**: access is now denied (FR-026, Edge Cases — the new setting is enforced on the next request, not
retroactively on an already-open tab).

## 6. Not Participated lockout

1. As the Organiser, use the existing 002 participant-management view to set one Participant's status to
   **Not Participated**.
2. As that user, open the homepage.

**Expected**: no Register/Reactivate link and no Revoke action; a message states the status was set by an
Organiser and only an Organiser can change it (FR-006a; `spec.md` Acceptance Scenario 1.7).

## Automated checks

- Unit: `mvn test -Dtest=ParticipantServiceTest,CustomFieldServiceTest,OrganiserSettingsServiceTest,IsoCountryCatalogTest`
- Integration (`WebTestClient` + Testcontainers): `mvn verify` runs every `*ManagementIT` in `participants/`,
  `organiser/customfield/`, `organiser/settings/`, including the capacity-race concurrent-submission test.
- Accessibility (Playwright + axe-core, per research.md §7): `mvn verify` also runs
  `a11y.RegistrationAccessibilityIT` and `a11y.ParticipantsDirectoryAccessibilityIT`, asserting zero
  critical/serious WCAG 2.1 AA violations on the registration form, self-edit form, directory table, and
  detail view (SC-009).

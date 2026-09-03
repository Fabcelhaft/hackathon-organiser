-- Schema initialisation for Hackathon Organiser
-- Convention: all DDL statements MUST use CREATE TABLE IF NOT EXISTS
-- to ensure idempotent re-execution on every application startup
-- (spring.sql.init.mode=always reruns this file on each restart).

-- User Story 1: Identity & Role Recognition (data-model.md "User")
-- id uses PostgreSQL 18's native uuidv7() as the column DEFAULT (research.md §1) — no
-- application-side ID generation exists anywhere in this codebase.
CREATE TABLE IF NOT EXISTS users (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    oidc_subject text NOT NULL,
    display_name text NOT NULL,
    email text,
    organiser boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS users_oidc_subject_key ON users (oidc_subject);

-- User Story 2: Skill catalog (data-model.md "Skill", FR-008, FR-008a)
CREATE TABLE IF NOT EXISTS skills (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    name text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Case-insensitive uniqueness (FR-008a): SkillService also pre-checks this to raise a friendly
-- domain error, but this index is the concurrency-safe backstop (research.md §4).
CREATE UNIQUE INDEX IF NOT EXISTS skills_name_lower_key ON skills (lower(name));

-- User Story 2: Custom Field Definition catalog (data-model.md "Custom Field Definition",
-- FR-011, FR-012, FR-012a, FR-026)
CREATE TABLE IF NOT EXISTS custom_field_definitions (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    label text NOT NULL,
    field_type text NOT NULL,
    required boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- User Story 2: Custom Field Option catalog (data-model.md "Custom Field Option",
-- FR-012, FR-012b) — only meaningful for MULTI_SELECT definitions
CREATE TABLE IF NOT EXISTS custom_field_options (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    custom_field_definition_id uuid NOT NULL REFERENCES custom_field_definitions (id),
    label text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Unique per definition, case-insensitively: CustomFieldService also pre-checks this to raise a
-- friendly domain error, but this index is the concurrency-safe backstop (research.md §4).
CREATE UNIQUE INDEX IF NOT EXISTS custom_field_options_definition_label_lower_key
    ON custom_field_options (custom_field_definition_id, lower(label));

-- User Story 3: Participant records (data-model.md "Participant", FR-006a, FR-006b, FR-007)
-- unique index on user_id enforces "at most one Participant per User" (FR-006a) as a
-- concurrency-safe backstop to ParticipantService's own pre-check (research.md §4).
CREATE TABLE IF NOT EXISTS participants (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    user_id uuid NOT NULL REFERENCES users (id),
    status text NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS participants_user_id_key ON participants (user_id);

-- User Story 3: Participant <-> Skill association (data-model.md "Pure association tables",
-- FR-009) — pure association table, composite PK, no independent UUID (excluded from FR-025 by
-- the spec's own Assumptions).
CREATE TABLE IF NOT EXISTS participant_skills (
    participant_id uuid NOT NULL REFERENCES participants (id),
    skill_id uuid NOT NULL REFERENCES skills (id),
    PRIMARY KEY (participant_id, skill_id)
);

-- User Story 3: Custom Field Value — a Participant's own answer to a Custom Field Definition
-- (data-model.md "Custom Field Value", FR-013, FR-014) — composite PK, "association carrying a
-- payload" (excluded from FR-025's UUIDv7 list by the spec's own Assumptions). free_text_value is
-- populated only for FREE_TEXT definitions; NULL for MULTI_SELECT rows, whose selections are
-- recorded in the child table below instead.
CREATE TABLE IF NOT EXISTS custom_field_values (
    participant_id uuid NOT NULL REFERENCES participants (id),
    custom_field_definition_id uuid NOT NULL REFERENCES custom_field_definitions (id),
    free_text_value text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (participant_id, custom_field_definition_id)
);

-- User Story 3: Custom Field Value Options — the selected options for a MULTI_SELECT Custom
-- Field Value (data-model.md "Custom Field Value Options", FR-014) — composite PK, composite FK
-- back to the owning custom_field_values row.
CREATE TABLE IF NOT EXISTS custom_field_value_options (
    participant_id uuid NOT NULL,
    custom_field_definition_id uuid NOT NULL,
    custom_field_option_id uuid NOT NULL REFERENCES custom_field_options (id),
    PRIMARY KEY (participant_id, custom_field_definition_id, custom_field_option_id),
    FOREIGN KEY (participant_id, custom_field_definition_id)
        REFERENCES custom_field_values (participant_id, custom_field_definition_id)
);

-- User Story 4: Topics (data-model.md "Topic", FR-015) — created_by_user_id is set once, at
-- creation, and never reassigned afterward; retained even if that User's access is later revoked
-- (edge case, spec.md).
CREATE TABLE IF NOT EXISTS topics (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    name text NOT NULL,
    description text NOT NULL,
    created_by_user_id uuid NOT NULL REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- User Story 4: Topic <-> Skill association (data-model.md "Pure association tables", FR-010) —
-- pure association table, composite PK, no independent UUID (excluded from FR-025 by the spec's
-- own Assumptions).
CREATE TABLE IF NOT EXISTS topic_skills (
    topic_id uuid NOT NULL REFERENCES topics (id),
    skill_id uuid NOT NULL REFERENCES skills (id),
    PRIMARY KEY (topic_id, skill_id)
);

-- User Story 5: Groups (data-model.md "Group", FR-016, FR-016a, FR-016b) — a formed team tied to
-- exactly one Topic. The partial unique index below is the concurrency-safe guarantee for "at
-- most one active Group per Topic" (FR-016a): GroupService also pre-checks this to raise a
-- friendly domain error, but only this index makes a race between two concurrent create requests
-- structurally impossible (research.md §4). Restricting to `WHERE status = 'ACTIVE'` (rather than
-- an unconditional unique constraint) is what allows a brand-new Group row for the same Topic
-- once the prior one is disbanded (FR-016b), while keeping the disbanded row queryable for
-- history.
CREATE TABLE IF NOT EXISTS groups (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    topic_id uuid NOT NULL REFERENCES topics (id),
    status text NOT NULL DEFAULT 'ACTIVE',
    disbanded_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS groups_topic_id_active_key ON groups (topic_id) WHERE status = 'ACTIVE';

-- User Story 5: Group Member (data-model.md "Group Member", FR-017, FR-016b) — composite PK,
-- "association carrying a payload" (excluded from FR-025's UUIDv7 list by the spec's own
-- Assumptions). `active` is flipped to false for every row of a Group when that Group is
-- disbanded, preserving the historical membership record instead of deleting it (FR-016b). The
-- partial unique index below is the concurrency-safe guarantee for "at most one active Group per
-- Participant" (FR-017), the same way the index above works for Topics: GroupService also
-- pre-checks this to raise a friendly domain error, but only this index makes a race between two
-- concurrent add-member requests structurally impossible (research.md §4).
CREATE TABLE IF NOT EXISTS group_members (
    group_id uuid NOT NULL REFERENCES groups (id),
    participant_id uuid NOT NULL REFERENCES participants (id),
    active boolean NOT NULL DEFAULT true,
    joined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, participant_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS group_members_participant_id_active_key
    ON group_members (participant_id) WHERE active;

-- Feature 003: Organiser Settings — a single global row gating self-registration,
-- self-revocation, and topic-approval (data-model.md "Organiser Settings", FR-023, FR-023a). The
-- unique index on `singleton` (unconditional, not partial — there's only ever one row, full stop)
-- guarantees exactly one row can ever exist (research.md §4), same pattern as the partial unique
-- indexes above just applied unconditionally. Seeded once, idempotently, right here rather than
-- via a CommandLineRunner (research.md §4).
CREATE TABLE IF NOT EXISTS organiser_settings (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    singleton boolean NOT NULL DEFAULT true,
    self_registration_enabled boolean NOT NULL DEFAULT true,
    self_revocation_enabled boolean NOT NULL DEFAULT true,
    topic_approval_required boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS organiser_settings_singleton_key ON organiser_settings (singleton);

INSERT INTO organiser_settings (singleton) VALUES (true) ON CONFLICT (singleton) DO NOTHING;

-- Feature 003: Topic approval workflow (data-model.md "Topic", research.md §6, FR-013, FR-016) —
-- set once at creation by TopicService.propose(...) from the current topic_approval_required
-- setting; never bulk-updated when that setting later changes (FR-016 is not retroactive).
ALTER TABLE topics ADD COLUMN IF NOT EXISTS approval_status text NOT NULL DEFAULT 'APPROVED';

-- Feature 003: Content Pages (data-model.md "Content Page", research.md §5, §6, FR-018-FR-020a).
-- The partial unique index guarantees "exactly one Content Page may be designated... the homepage
-- page" (FR-019) at the database level; ContentPageService must un-set the previous is_homepage
-- row in the same write or this index rejects it.
CREATE TABLE IF NOT EXISTS content_pages (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    title text NOT NULL,
    body_markdown text NOT NULL,
    sort_index integer NOT NULL DEFAULT 0,
    is_homepage boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS content_pages_is_homepage_key ON content_pages (is_homepage) WHERE is_homepage;

-- FR-019a: fires only when the table is completely empty (first-ever startup) — an Organiser who
-- later deletes this placeholder made a deliberate choice; it is not re-seeded on next restart.
INSERT INTO content_pages (title, body_markdown, sort_index, is_homepage)
SELECT 'Welcome', '# Welcome to the Hackathon', 0, true
WHERE NOT EXISTS (SELECT 1 FROM content_pages);

-- Feature 003: Content Images (data-model.md "Content Image", research.md §2, §3, FR-024-FR-029).
-- No FK from content_pages to this table: the reference lives inside body_markdown as a literal
-- /content-images/{id} path string, not a foreign key column (research.md §3) — deletion-blocking
-- is a query-time substring search, entirely ContentImageService's responsibility.
CREATE TABLE IF NOT EXISTS content_images (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    alt_text text NOT NULL,
    content_type text NOT NULL,
    byte_size integer NOT NULL,
    data bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Feature 004: Custom Field Definition extensions — SINGLE_SELECT/COUNTRY field types (added as
-- new field_type values, no column change), visibility flags, and the built-in Country field
-- (data-model.md "Custom Field Definition", research.md §1, §3; FR-013-FR-017).
ALTER TABLE custom_field_definitions ADD COLUMN IF NOT EXISTS public boolean NOT NULL DEFAULT false;
ALTER TABLE custom_field_definitions ADD COLUMN IF NOT EXISTS overview boolean NOT NULL DEFAULT false;
ALTER TABLE custom_field_definitions ADD COLUMN IF NOT EXISTS enabled boolean NOT NULL DEFAULT true;

-- At most one COUNTRY-typed definition can ever exist (FR-013).
CREATE UNIQUE INDEX IF NOT EXISTS custom_field_definitions_country_key
    ON custom_field_definitions (field_type) WHERE field_type = 'COUNTRY';

-- Seeded once, idempotently: an Organiser must explicitly enable it (private/off-by-default
-- posture, data-model.md).
INSERT INTO custom_field_definitions (label, field_type, required, public, overview, enabled)
SELECT 'Country', 'COUNTRY', false, false, false, false
WHERE NOT EXISTS (SELECT 1 FROM custom_field_definitions WHERE field_type = 'COUNTRY');

-- Feature 004: Organiser Settings extensions — registration cap, self-edit gate, skill
-- visibility, and the configurable Participants directory audience (data-model.md "Organiser
-- Settings", research.md §5; FR-007, FR-018, FR-021, FR-025).
ALTER TABLE organiser_settings ADD COLUMN IF NOT EXISTS max_registrations integer;

-- ALTER TABLE ... ADD CONSTRAINT has no native IF NOT EXISTS form in PostgreSQL, and this file's
-- statements are split/executed one at a time by Spring's plain `;`-delimited script runner (no
-- $$-quoted PL/pgSQL block support) — so idempotent re-runs use DROP CONSTRAINT IF EXISTS followed
-- by a plain ADD CONSTRAINT instead, the same net effect with only single-line statements.
ALTER TABLE organiser_settings DROP CONSTRAINT IF EXISTS organiser_settings_max_registrations_check;
ALTER TABLE organiser_settings
    ADD CONSTRAINT organiser_settings_max_registrations_check
    CHECK (max_registrations IS NULL OR max_registrations >= 1);

ALTER TABLE organiser_settings ADD COLUMN IF NOT EXISTS self_edit_enabled boolean NOT NULL DEFAULT true;
ALTER TABLE organiser_settings ADD COLUMN IF NOT EXISTS skill_visibility_enabled boolean NOT NULL DEFAULT false;
ALTER TABLE organiser_settings
    ADD COLUMN IF NOT EXISTS participants_directory_audience text NOT NULL DEFAULT 'ORGANISERS_ONLY';

-- Feature 005: Organiser Settings extensions — Compliance Ruleset (max/min Group Members),
-- Topic-joining toggle, and Skill Display Mode (data-model.md "Organiser Settings", research.md
-- §3; FR-011, FR-011a-d, FR-017, FR-020a, FR-020d). The DEFAULT clause on max_group_members is
-- what "seeds" a Compliance Ruleset for every existing and future singleton row (FR-011c) — no
-- CommandLineRunner or extra seed INSERT needed (research.md §3).
ALTER TABLE organiser_settings ADD COLUMN IF NOT EXISTS max_group_members integer NOT NULL DEFAULT 5;
ALTER TABLE organiser_settings ADD COLUMN IF NOT EXISTS min_group_members integer;
ALTER TABLE organiser_settings ADD COLUMN IF NOT EXISTS topic_joining_enabled boolean NOT NULL DEFAULT true;
ALTER TABLE organiser_settings
    ADD COLUMN IF NOT EXISTS skill_display_mode text NOT NULL DEFAULT 'STILL_NEEDED_ONLY';

-- Whether the Compliance indicator is shown to non-Organiser viewers on the shared Topic overview
-- and Topic detail pages; Organisers always see it regardless of this toggle.
ALTER TABLE organiser_settings
    ADD COLUMN IF NOT EXISTS compliance_visible_to_participants boolean NOT NULL DEFAULT true;

ALTER TABLE organiser_settings DROP CONSTRAINT IF EXISTS organiser_settings_max_group_members_check;
ALTER TABLE organiser_settings
    ADD CONSTRAINT organiser_settings_max_group_members_check CHECK (max_group_members >= 1);

ALTER TABLE organiser_settings DROP CONSTRAINT IF EXISTS organiser_settings_min_le_max_group_members_check;
ALTER TABLE organiser_settings
    ADD CONSTRAINT organiser_settings_min_le_max_group_members_check
    CHECK (min_group_members IS NULL OR min_group_members <= max_group_members);

-- Feature 005: Group compliance override (data-model.md "Group", research.md §4; FR-014, FR-015,
-- FR-016) — a single boolean, set/cleared only by GroupService.setComplianceOverride, that lets
-- ComplianceService.evaluate short-circuit to COMPLIANT_OVERRIDE and GroupService.join skip the
-- Maximum Group Members cap for this specific Group.
ALTER TABLE groups ADD COLUMN IF NOT EXISTS compliance_override boolean NOT NULL DEFAULT false;

-- Feature 005: Custom Field Diversity Requirements — one row per configured requirement
-- (data-model.md "Custom Field Diversity Requirement", research.md §3; FR-011, FR-011d, FR-012a).
-- A real one-to-many collection with its own payload (minimum_distinct_values), not a pure
-- association table, so it gets its own table + repository rather than a DatabaseClient-backed
-- composite-key table. The unique index caps it to at most one requirement per Custom Field.
CREATE TABLE IF NOT EXISTS compliance_diversity_requirements (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    custom_field_definition_id uuid NOT NULL REFERENCES custom_field_definitions (id),
    minimum_distinct_values integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE compliance_diversity_requirements
    DROP CONSTRAINT IF EXISTS compliance_diversity_requirements_minimum_check;
ALTER TABLE compliance_diversity_requirements
    ADD CONSTRAINT compliance_diversity_requirements_minimum_check CHECK (minimum_distinct_values >= 2);

CREATE UNIQUE INDEX IF NOT EXISTS compliance_diversity_requirements_field_key
    ON compliance_diversity_requirements (custom_field_definition_id);

-- Data fix: some IdPs wrap the display name in stray square brackets (e.g. "[Jane Doe]"). Strips a
-- leading '[' and a trailing ']' independently (either alone is still removed), then trims
-- whitespace left behind — mirrors HackathonOidcUserService#stripBrackets exactly, applied here
-- once to every existing row (new logins are normalised going forward by that same method). Each
-- statement is a no-op on re-run once applied.
UPDATE users SET display_name = substring(display_name from 2)
WHERE left(display_name, 1) = '[';

UPDATE users SET display_name = left(display_name, length(display_name) - 1)
WHERE right(display_name, 1) = ']';

UPDATE users SET display_name = btrim(display_name)
WHERE display_name <> btrim(display_name);

-- Microsoft Teams chat links: a global, off-by-default toggle that (when enabled) turns a
-- person's displayed name into a link opening a Teams chat with them, wherever their email is
-- already available in that read model.
ALTER TABLE organiser_settings ADD COLUMN IF NOT EXISTS teams_links_enabled boolean NOT NULL DEFAULT false;

-- Feature 006: Audit Trail for Topics and Participants (data-model.md "Audit Entry"; FR-001-FR-011a).
-- One shared, generic table for every audited event across Topic, Group (filed against its own
-- Topic — Groups have no audit trail of their own, research.md §3/§9), and Participant. subject_type/
-- subject_id are a generic, FK-less reference (no topic_id/group_id/participant_id columns) so a
-- Participant's hard-delete never needs an ON DELETE rule; subject_label is a denormalized snapshot
-- so an entry stays legible even after that happens.
CREATE TABLE IF NOT EXISTS audit_entries (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    event_type text NOT NULL,
    actor_user_id uuid NOT NULL REFERENCES users (id),
    organiser boolean NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    subject_type text NOT NULL,
    subject_id uuid NOT NULL,
    subject_label text NOT NULL,
    old_value text,
    new_value text,
    action_id uuid
);

CREATE INDEX IF NOT EXISTS audit_entries_subject_idx
    ON audit_entries (subject_type, subject_id, occurred_at DESC);

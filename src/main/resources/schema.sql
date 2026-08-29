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

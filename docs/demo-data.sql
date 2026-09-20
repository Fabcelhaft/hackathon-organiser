-- Hackathon Organiser — Demo Data
--
-- A hand-authored, idempotent seed script for a single showcase hackathon: two organisers, eight
-- participants, a skill catalog, three custom fields (plus the built-in Country field), five
-- topics (three approved with groups, two still pending), a Kafka destination, an HTTP webhook,
-- one Task Rule with a few generated Tasks, two extra content pages, a Compliance diversity
-- requirement, and a handful of audit entries.
--
-- This is NOT part of the application's own startup path (schema.sql is). Run it by hand against
-- a database that has already had schema.sql applied — simplest way: start the app/db once
-- (`docker compose up db -d` is enough; spring.sql.init.mode=always runs schema.sql on the app's
-- own startup, so either start the full `app` service once, or apply schema.sql yourself), then:
--
--   docker compose exec -T db psql -U hackathon -d hackathon < docs/demo-data.sql
--
-- Safe to re-run: every statement below is either an idempotent INSERT (`ON CONFLICT ... DO
-- NOTHING`, matching schema.sql's own convention) or an UPDATE that just reasserts the same
-- values. All ids are fixed literals (not the schema's default uuidv7()) so rows can reference
-- each other directly and re-runs are trivially recognisable.
--
-- Binary-payload tables (content_images, topic_attachments) are intentionally NOT seeded here —
-- a hand-authored SQL file is the wrong place for bytea blobs.
--
-- KEEP THIS FILE IN SYNC WITH THE DATA MODEL — see CLAUDE.md.

BEGIN;

-- ---------------------------------------------------------------------------------------------
-- Organiser Settings — a couple of tweaks so the demo data below actually demonstrates something
-- (a minimum group size to make some Groups non-compliant, Skill visibility and the Participants
-- directory switched on, Teams links on). The singleton row itself is seeded by schema.sql.
-- ---------------------------------------------------------------------------------------------
UPDATE organiser_settings
SET min_group_members = 2,
    skill_visibility_enabled = true,
    participants_directory_audience = 'ORGANISERS_AND_PARTICIPANTS',
    teams_links_enabled = true
WHERE singleton = true;

-- ---------------------------------------------------------------------------------------------
-- Users
-- ---------------------------------------------------------------------------------------------
-- The real dev-Dex login (dex/config.yaml staticPasswords: organiser@example.dev, userID
-- 08a8684b-db88-4b73-90a9-3cd1661f5466 — Dex issues that userID back as the OIDC `sub` claim,
-- which becomes this row's oidc_subject). Upserted on oidc_subject with DO UPDATE, not DO
-- NOTHING, so this specific person ends up an Organiser whether this script runs before their
-- first login (inserts the row pre-flagged) or after (HackathonOidcUserService already created
-- the row on login, and this flips it to Organiser).
INSERT INTO users (id, oidc_subject, display_name, email, organiser)
VALUES ('a1000000-0000-0000-0000-000000000001', '08a8684b-db88-4b73-90a9-3cd1661f5466',
        'Dev Organiser', 'organiser@example.dev', true)
ON CONFLICT (oidc_subject) DO UPDATE SET organiser = true;

INSERT INTO users (id, oidc_subject, display_name, email, organiser) VALUES
    ('a1000000-0000-0000-0000-000000000002', 'demo-oidc-organiser-02', 'Priya Organiser', 'priya.organiser@example.dev', true),
    ('a1000000-0000-0000-0000-000000000003', 'demo-oidc-participant-03', 'Jordan Lee', 'jordan.lee@example.dev', false),
    ('a1000000-0000-0000-0000-000000000004', 'demo-oidc-participant-04', 'Sam Rivera', 'sam.rivera@example.dev', false),
    ('a1000000-0000-0000-0000-000000000005', 'demo-oidc-participant-05', 'Noah Kim', 'noah.kim@example.dev', false),
    ('a1000000-0000-0000-0000-000000000006', 'demo-oidc-participant-06', 'Maria Silva', 'maria.silva@example.dev', false),
    ('a1000000-0000-0000-0000-000000000007', 'demo-oidc-participant-07', 'Chen Wei', 'chen.wei@example.dev', false),
    ('a1000000-0000-0000-0000-000000000008', 'demo-oidc-participant-08', 'Fatima Noor', 'fatima.noor@example.dev', false),
    ('a1000000-0000-0000-0000-000000000009', 'demo-oidc-participant-09', 'Liam O''Brien', 'liam.obrien@example.dev', false),
    ('a1000000-0000-0000-0000-00000000000a', 'demo-oidc-participant-0a', 'Aisha Bello', 'aisha.bello@example.dev', false)
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- Skills
-- ---------------------------------------------------------------------------------------------
INSERT INTO skills (id, name) VALUES
    ('a2000000-0000-0000-0000-000000000001', 'Java'),
    ('a2000000-0000-0000-0000-000000000002', 'React'),
    ('a2000000-0000-0000-0000-000000000003', 'UX Design'),
    ('a2000000-0000-0000-0000-000000000004', 'Data Science'),
    ('a2000000-0000-0000-0000-000000000005', 'DevOps'),
    ('a2000000-0000-0000-0000-000000000006', 'Product Management'),
    ('a2000000-0000-0000-0000-000000000007', 'Marketing'),
    ('a2000000-0000-0000-0000-000000000008', 'Public Speaking')
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- Custom Field Definitions & Options
-- ---------------------------------------------------------------------------------------------
INSERT INTO custom_field_definitions (id, label, field_type, required, public, overview, enabled, sort_index) VALUES
    ('a3000000-0000-0000-0000-000000000001', 'T-shirt size', 'FREE_TEXT', true, false, false, true, 1),
    ('a3000000-0000-0000-0000-000000000002', 'Dietary restrictions', 'MULTI_SELECT', false, true, true, true, 2),
    ('a3000000-0000-0000-0000-000000000003', 'Experience level', 'SINGLE_SELECT', false, true, true, true, 3)
ON CONFLICT (id) DO NOTHING;

INSERT INTO custom_field_options (id, custom_field_definition_id, label, sort_index) VALUES
    ('a4000000-0000-0000-0000-000000000001', 'a3000000-0000-0000-0000-000000000002', 'Vegetarian', 1),
    ('a4000000-0000-0000-0000-000000000002', 'a3000000-0000-0000-0000-000000000002', 'Vegan', 2),
    ('a4000000-0000-0000-0000-000000000003', 'a3000000-0000-0000-0000-000000000002', 'Gluten-free', 3),
    ('a4000000-0000-0000-0000-000000000004', 'a3000000-0000-0000-0000-000000000003', 'Beginner', 1),
    ('a4000000-0000-0000-0000-000000000005', 'a3000000-0000-0000-0000-000000000003', 'Intermediate', 2),
    ('a4000000-0000-0000-0000-000000000006', 'a3000000-0000-0000-0000-000000000003', 'Advanced', 3)
ON CONFLICT (id) DO NOTHING;

-- The built-in COUNTRY field is seeded disabled by schema.sql — switch it on so the demo data
-- below (and the app's own forms) actually show it. Not re-inserted: schema.sql already seeded
-- it once, with its own uuidv7() id, so there is no fixed literal id to reference; every later
-- reference to it below looks its id up by field_type instead.
UPDATE custom_field_definitions SET enabled = true WHERE field_type = 'COUNTRY';

-- ---------------------------------------------------------------------------------------------
-- Participants (one per non-organiser user) & their Skills
-- ---------------------------------------------------------------------------------------------
INSERT INTO participants (id, user_id, status) VALUES
    ('a5000000-0000-0000-0000-000000000003', 'a1000000-0000-0000-0000-000000000003', 'ACTIVE'),
    ('a5000000-0000-0000-0000-000000000004', 'a1000000-0000-0000-0000-000000000004', 'ACTIVE'),
    ('a5000000-0000-0000-0000-000000000005', 'a1000000-0000-0000-0000-000000000005', 'ACTIVE'),
    ('a5000000-0000-0000-0000-000000000006', 'a1000000-0000-0000-0000-000000000006', 'ACTIVE'),
    ('a5000000-0000-0000-0000-000000000007', 'a1000000-0000-0000-0000-000000000007', 'ACTIVE'),
    ('a5000000-0000-0000-0000-000000000008', 'a1000000-0000-0000-0000-000000000008', 'ACTIVE'),
    ('a5000000-0000-0000-0000-000000000009', 'a1000000-0000-0000-0000-000000000009', 'NOT_PARTICIPATED'),
    ('a5000000-0000-0000-0000-00000000000a', 'a1000000-0000-0000-0000-00000000000a', 'REVOKED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO participant_skills (participant_id, skill_id) VALUES
    ('a5000000-0000-0000-0000-000000000003', 'a2000000-0000-0000-0000-000000000001'), -- Jordan: Java
    ('a5000000-0000-0000-0000-000000000003', 'a2000000-0000-0000-0000-000000000002'), -- Jordan: React
    ('a5000000-0000-0000-0000-000000000004', 'a2000000-0000-0000-0000-000000000003'), -- Sam: UX Design
    ('a5000000-0000-0000-0000-000000000004', 'a2000000-0000-0000-0000-000000000006'), -- Sam: Product Management
    ('a5000000-0000-0000-0000-000000000005', 'a2000000-0000-0000-0000-000000000004'), -- Noah: Data Science
    ('a5000000-0000-0000-0000-000000000005', 'a2000000-0000-0000-0000-000000000005'), -- Noah: DevOps
    ('a5000000-0000-0000-0000-000000000006', 'a2000000-0000-0000-0000-000000000001'), -- Maria: Java
    ('a5000000-0000-0000-0000-000000000006', 'a2000000-0000-0000-0000-000000000008'), -- Maria: Public Speaking
    ('a5000000-0000-0000-0000-000000000007', 'a2000000-0000-0000-0000-000000000002'), -- Chen: React
    ('a5000000-0000-0000-0000-000000000007', 'a2000000-0000-0000-0000-000000000004'), -- Chen: Data Science
    ('a5000000-0000-0000-0000-000000000008', 'a2000000-0000-0000-0000-000000000007'), -- Fatima: Marketing
    ('a5000000-0000-0000-0000-000000000008', 'a2000000-0000-0000-0000-000000000006'), -- Fatima: Product Management
    ('a5000000-0000-0000-0000-000000000009', 'a2000000-0000-0000-0000-000000000005'), -- Liam: DevOps
    ('a5000000-0000-0000-0000-00000000000a', 'a2000000-0000-0000-0000-000000000003')  -- Aisha: UX Design
ON CONFLICT (participant_id, skill_id) DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- Custom Field Values
-- ---------------------------------------------------------------------------------------------
-- T-shirt size (FREE_TEXT)
INSERT INTO custom_field_values (participant_id, custom_field_definition_id, free_text_value) VALUES
    ('a5000000-0000-0000-0000-000000000003', 'a3000000-0000-0000-0000-000000000001', 'M'),
    ('a5000000-0000-0000-0000-000000000004', 'a3000000-0000-0000-0000-000000000001', 'L'),
    ('a5000000-0000-0000-0000-000000000005', 'a3000000-0000-0000-0000-000000000001', 'S'),
    ('a5000000-0000-0000-0000-000000000006', 'a3000000-0000-0000-0000-000000000001', 'XL'),
    ('a5000000-0000-0000-0000-000000000007', 'a3000000-0000-0000-0000-000000000001', 'M'),
    ('a5000000-0000-0000-0000-000000000008', 'a3000000-0000-0000-0000-000000000001', 'L'),
    ('a5000000-0000-0000-0000-000000000009', 'a3000000-0000-0000-0000-000000000001', 'M'),
    ('a5000000-0000-0000-0000-00000000000a', 'a3000000-0000-0000-0000-000000000001', 'S')
ON CONFLICT (participant_id, custom_field_definition_id) DO NOTHING;

-- Dietary restrictions (MULTI_SELECT) — the value row itself carries no free text; selections go
-- in custom_field_value_options below.
INSERT INTO custom_field_values (participant_id, custom_field_definition_id, free_text_value) VALUES
    ('a5000000-0000-0000-0000-000000000003', 'a3000000-0000-0000-0000-000000000002', NULL),
    ('a5000000-0000-0000-0000-000000000005', 'a3000000-0000-0000-0000-000000000002', NULL),
    ('a5000000-0000-0000-0000-000000000007', 'a3000000-0000-0000-0000-000000000002', NULL)
ON CONFLICT (participant_id, custom_field_definition_id) DO NOTHING;

INSERT INTO custom_field_value_options (participant_id, custom_field_definition_id, custom_field_option_id) VALUES
    ('a5000000-0000-0000-0000-000000000003', 'a3000000-0000-0000-0000-000000000002', 'a4000000-0000-0000-0000-000000000001'), -- Jordan: Vegetarian
    ('a5000000-0000-0000-0000-000000000005', 'a3000000-0000-0000-0000-000000000002', 'a4000000-0000-0000-0000-000000000002'), -- Noah: Vegan
    ('a5000000-0000-0000-0000-000000000005', 'a3000000-0000-0000-0000-000000000002', 'a4000000-0000-0000-0000-000000000003'), -- Noah: Gluten-free
    ('a5000000-0000-0000-0000-000000000007', 'a3000000-0000-0000-0000-000000000002', 'a4000000-0000-0000-0000-000000000003')  -- Chen: Gluten-free
ON CONFLICT (participant_id, custom_field_definition_id, custom_field_option_id) DO NOTHING;

-- Experience level (SINGLE_SELECT) — feeds the Compliance diversity requirement seeded below.
INSERT INTO custom_field_values (participant_id, custom_field_definition_id, free_text_value) VALUES
    ('a5000000-0000-0000-0000-000000000003', 'a3000000-0000-0000-0000-000000000003', NULL),
    ('a5000000-0000-0000-0000-000000000004', 'a3000000-0000-0000-0000-000000000003', NULL),
    ('a5000000-0000-0000-0000-000000000005', 'a3000000-0000-0000-0000-000000000003', NULL),
    ('a5000000-0000-0000-0000-000000000006', 'a3000000-0000-0000-0000-000000000003', NULL),
    ('a5000000-0000-0000-0000-000000000007', 'a3000000-0000-0000-0000-000000000003', NULL),
    ('a5000000-0000-0000-0000-000000000008', 'a3000000-0000-0000-0000-000000000003', NULL)
ON CONFLICT (participant_id, custom_field_definition_id) DO NOTHING;

INSERT INTO custom_field_value_options (participant_id, custom_field_definition_id, custom_field_option_id) VALUES
    ('a5000000-0000-0000-0000-000000000003', 'a3000000-0000-0000-0000-000000000003', 'a4000000-0000-0000-0000-000000000005'), -- Jordan: Intermediate
    ('a5000000-0000-0000-0000-000000000004', 'a3000000-0000-0000-0000-000000000003', 'a4000000-0000-0000-0000-000000000004'), -- Sam: Beginner
    ('a5000000-0000-0000-0000-000000000005', 'a3000000-0000-0000-0000-000000000003', 'a4000000-0000-0000-0000-000000000006'), -- Noah: Advanced
    ('a5000000-0000-0000-0000-000000000006', 'a3000000-0000-0000-0000-000000000003', 'a4000000-0000-0000-0000-000000000005'), -- Maria: Intermediate
    ('a5000000-0000-0000-0000-000000000007', 'a3000000-0000-0000-0000-000000000003', 'a4000000-0000-0000-0000-000000000004'), -- Chen: Beginner
    ('a5000000-0000-0000-0000-000000000008', 'a3000000-0000-0000-0000-000000000003', 'a4000000-0000-0000-0000-000000000006')  -- Fatima: Advanced
ON CONFLICT (participant_id, custom_field_definition_id, custom_field_option_id) DO NOTHING;

-- Country (built-in, stored as a free-text ISO 3166-1 alpha-2 code — see IsoCountryCatalog /
-- CustomFieldService, which reads it the same way as any other FREE_TEXT value).
INSERT INTO custom_field_values (participant_id, custom_field_definition_id, free_text_value)
SELECT 'a5000000-0000-0000-0000-000000000004'::uuid, id, 'ES' FROM custom_field_definitions WHERE field_type = 'COUNTRY'
UNION ALL
SELECT 'a5000000-0000-0000-0000-000000000007'::uuid, id, 'DE' FROM custom_field_definitions WHERE field_type = 'COUNTRY'
ON CONFLICT (participant_id, custom_field_definition_id) DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- Topics & their Skills
-- ---------------------------------------------------------------------------------------------
INSERT INTO topics (id, name, description, created_by_user_id, approval_status) VALUES
    ('a6000000-0000-0000-0000-000000000001', 'Realtime Compliance Dashboard',
     'A live view of team formation and topic compliance status for organisers.',
     'a1000000-0000-0000-0000-000000000003', 'APPROVED'),
    ('a6000000-0000-0000-0000-000000000002', 'AI Icebreaker Bot',
     'A chatbot that helps new participants find teammates based on shared skills.',
     'a1000000-0000-0000-0000-000000000004', 'APPROVED'),
    ('a6000000-0000-0000-0000-000000000003', 'Sustainable Swag Tracker',
     'Track and reduce hackathon merchandise waste with a simple inventory app.',
     'a1000000-0000-0000-0000-000000000005', 'APPROVED'),
    ('a6000000-0000-0000-0000-000000000004', 'Hackathon Health Check',
     'A wellbeing check-in tool for long hacking sessions.',
     'a1000000-0000-0000-0000-000000000006', 'PENDING'),
    ('a6000000-0000-0000-0000-000000000005', 'Open Mic Pitch make sure that the organiser@example.dev user from dex also has organiser rightsNight',
     'A lightweight scheduling app for the closing pitch session.',
     'a1000000-0000-0000-0000-000000000007', 'PENDING')
ON CONFLICT (id) DO NOTHING;

INSERT INTO topic_skills (topic_id, skill_id) VALUES
    ('a6000000-0000-0000-0000-000000000001', 'a2000000-0000-0000-0000-000000000001'), -- Java
    ('a6000000-0000-0000-0000-000000000001', 'a2000000-0000-0000-0000-000000000005'), -- DevOps
    ('a6000000-0000-0000-0000-000000000002', 'a2000000-0000-0000-0000-000000000004'), -- Data Science
    ('a6000000-0000-0000-0000-000000000002', 'a2000000-0000-0000-0000-000000000003'), -- UX Design
    ('a6000000-0000-0000-0000-000000000003', 'a2000000-0000-0000-0000-000000000002'), -- React
    ('a6000000-0000-0000-0000-000000000003', 'a2000000-0000-0000-0000-000000000006'), -- Product Management
    ('a6000000-0000-0000-0000-000000000004', 'a2000000-0000-0000-0000-000000000003'), -- UX Design
    ('a6000000-0000-0000-0000-000000000004', 'a2000000-0000-0000-0000-000000000008'), -- Public Speaking
    ('a6000000-0000-0000-0000-000000000005', 'a2000000-0000-0000-0000-000000000006'), -- Product Management
    ('a6000000-0000-0000-0000-000000000005', 'a2000000-0000-0000-0000-000000000007')  -- Marketing
ON CONFLICT (topic_id, skill_id) DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- Groups & Members — three scenarios: NOT_COMPLIANT (below the min_group_members=2 set above,
-- and only one distinct Experience level), COMPLIANT (3 members, 3 distinct Experience levels),
-- and COMPLIANT_OVERRIDE (would fail both checks on its own, forced compliant by an Organiser).
-- ---------------------------------------------------------------------------------------------
INSERT INTO groups (id, topic_id, status, compliance_override) VALUES
    ('a7000000-0000-0000-0000-000000000001', 'a6000000-0000-0000-0000-000000000001', 'ACTIVE', false),
    ('a7000000-0000-0000-0000-000000000002', 'a6000000-0000-0000-0000-000000000002', 'ACTIVE', false),
    ('a7000000-0000-0000-0000-000000000003', 'a6000000-0000-0000-0000-000000000003', 'ACTIVE', true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO group_members (group_id, participant_id, active) VALUES
    ('a7000000-0000-0000-0000-000000000001', 'a5000000-0000-0000-0000-000000000003', true), -- Jordan only -> NOT_COMPLIANT
    ('a7000000-0000-0000-0000-000000000002', 'a5000000-0000-0000-0000-000000000004', true), -- Sam
    ('a7000000-0000-0000-0000-000000000002', 'a5000000-0000-0000-0000-000000000005', true), -- Noah
    ('a7000000-0000-0000-0000-000000000002', 'a5000000-0000-0000-0000-000000000006', true), -- Maria -> COMPLIANT
    ('a7000000-0000-0000-0000-000000000003', 'a5000000-0000-0000-0000-000000000007', true)  -- Chen only -> COMPLIANT_OVERRIDE
ON CONFLICT (group_id, participant_id) DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- Compliance Diversity Requirement — the Groups above are deliberately sized to demonstrate it.
-- ---------------------------------------------------------------------------------------------
INSERT INTO compliance_diversity_requirements (id, custom_field_definition_id, minimum_distinct_values) VALUES
    ('a8000000-0000-0000-0000-000000000001', 'a3000000-0000-0000-0000-000000000003', 2)
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- Event Destinations (Kafka, HTTP POST) and a Task Rule, with their Event Type subscriptions
-- ---------------------------------------------------------------------------------------------
INSERT INTO event_destinations
    (id, name, type, enabled, kafka_bootstrap_servers, kafka_topic, http_url, credential,
     task_title_pattern, task_default_assignee_user_id)
VALUES
    ('a9000000-0000-0000-0000-000000000001', 'Team formation feed', 'KAFKA', true,
     'kafka:19092', 'hackathon.events', NULL, NULL, NULL, NULL),
    ('a9000000-0000-0000-0000-000000000002', 'Ops webhook', 'HTTP_POST', true,
     NULL, NULL, 'https://ops.example.dev/webhooks/hackathon', 'demo-shared-secret', NULL, NULL),
    ('a9000000-0000-0000-0000-000000000003', 'Welcome new participants', 'TASK', true,
     NULL, NULL, NULL, NULL, 'Welcome {{user.displayName}} to the hackathon!',
     'a1000000-0000-0000-0000-000000000001')
ON CONFLICT (id) DO NOTHING;

INSERT INTO event_destination_event_types (event_destination_id, event_type) VALUES
    ('a9000000-0000-0000-0000-000000000001', 'GROUP_FORMED'),
    ('a9000000-0000-0000-0000-000000000001', 'GROUP_DISBANDED'),
    ('a9000000-0000-0000-0000-000000000002', 'PARTICIPANT_REGISTERED'),
    ('a9000000-0000-0000-0000-000000000002', 'TOPIC_APPROVED'),
    ('a9000000-0000-0000-0000-000000000003', 'PARTICIPANT_REGISTERED')
ON CONFLICT (event_destination_id, event_type) DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- Tasks generated by the "Welcome new participants" Task Rule — mixed done/undone, one
-- unassigned, to show the range of states the Task list renders (FR-016, FR-017, FR-022, FR-028).
-- ---------------------------------------------------------------------------------------------
INSERT INTO tasks (id, event_destination_id, rule_name, event_type, title, assignee_user_id, done, done_at) VALUES
    ('aa000000-0000-0000-0000-000000000001', 'a9000000-0000-0000-0000-000000000003',
     'Welcome new participants', 'PARTICIPANT_REGISTERED', 'Welcome Jordan Lee to the hackathon!',
     'a1000000-0000-0000-0000-000000000001', false, NULL),
    ('aa000000-0000-0000-0000-000000000002', 'a9000000-0000-0000-0000-000000000003',
     'Welcome new participants', 'PARTICIPANT_REGISTERED', 'Welcome Sam Rivera to the hackathon!',
     'a1000000-0000-0000-0000-000000000001', true, now() - interval '2 days'),
    ('aa000000-0000-0000-0000-000000000003', 'a9000000-0000-0000-0000-000000000003',
     'Welcome new participants', 'PARTICIPANT_REGISTERED', 'Welcome Noah Kim to the hackathon!',
     'a1000000-0000-0000-0000-000000000002', false, NULL),
    ('aa000000-0000-0000-0000-000000000004', 'a9000000-0000-0000-0000-000000000003',
     'Welcome new participants', 'PARTICIPANT_REGISTERED', 'Welcome Maria Silva to the hackathon!',
     NULL, false, NULL)
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- Content Pages — HOMEPAGE is already seeded by schema.sql ("Welcome"); add the other two
-- configurable contexts so the Topic-creation and Registration flows have real copy.
-- ---------------------------------------------------------------------------------------------
INSERT INTO content_pages (id, title, body_markdown, sort_index, context) VALUES
    ('ab000000-0000-0000-0000-000000000001', 'How to propose a great Topic',
     '# Proposing a Topic' || chr(10) || chr(10) ||
     'Keep the name short and the description concrete: what will your team actually build, ' ||
     'and who is it for? Topics with a clear problem statement attract teammates faster.',
     0, 'TOPIC_CREATION'),
    ('ab000000-0000-0000-0000-000000000002', 'Welcome, new participant!',
     '# Welcome!' || chr(10) || chr(10) ||
     'Thanks for registering. Add your Skills and answer the optional fields below so other ' ||
     'participants and Organisers can see what you bring to a team.',
     0, 'USER_REGISTRATION')
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- Audit Entries — a short, plausible history for two Topics and two Participants.
-- ---------------------------------------------------------------------------------------------
INSERT INTO audit_entries
    (id, event_type, actor_user_id, organiser, occurred_at, subject_type, subject_id, subject_label, old_value, new_value)
VALUES
    ('ac000000-0000-0000-0000-000000000001', 'CREATED', 'a1000000-0000-0000-0000-000000000003', false,
     now() - interval '10 days', 'TOPIC', 'a6000000-0000-0000-0000-000000000001',
     'Realtime Compliance Dashboard', NULL, NULL),
    ('ac000000-0000-0000-0000-000000000002', 'JOINED', 'a1000000-0000-0000-0000-000000000004', false,
     now() - interval '9 days', 'TOPIC', 'a6000000-0000-0000-0000-000000000002',
     'AI Icebreaker Bot', NULL, NULL),
    ('ac000000-0000-0000-0000-000000000003', 'STATUS_CHANGED', 'a1000000-0000-0000-0000-000000000001', true,
     now() - interval '5 days', 'PARTICIPANT', 'a5000000-0000-0000-0000-000000000009',
     'Liam O''Brien', 'ACTIVE', 'NOT_PARTICIPATED'),
    ('ac000000-0000-0000-0000-000000000004', 'STATUS_CHANGED', 'a1000000-0000-0000-0000-000000000001', true,
     now() - interval '4 days', 'PARTICIPANT', 'a5000000-0000-0000-0000-00000000000a',
     'Aisha Bello', 'ACTIVE', 'REVOKED')
ON CONFLICT (id) DO NOTHING;

COMMIT;

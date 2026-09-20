# Repository rules for Claude

## Keep the demo data in sync with the data model

[`docs/demo-data.sql`](docs/demo-data.sql) is a hand-authored, idempotent seed script that fills
the database with realistic demo data (users, participants, topics, groups, event destinations,
tasks, etc.) for showcasing the app. It is not run automatically — `schema.sql` is the only
startup-time SQL.

Whenever a change to `src/main/resources/schema.sql` adds, removes, or renames a table, column,
or enum-backed value that is part of the shape this demo data covers, update
`docs/demo-data.sql` in the same change:

- A new table gets a few illustrative demo rows, wired up via the fixed literal UUIDs already
  used in the file (pick the next free suffix under that table's id prefix).
- A new required column on an existing table gets a realistic value added to that table's
  existing demo inserts.
- A new enum value gets at least one demo row exercising it, where that's easy to show off.

Keep every insert idempotent the same way the existing ones are: `ON CONFLICT (id) DO NOTHING`
for tables with a UUID primary key, or `ON CONFLICT (<composite key columns>) DO NOTHING` for
pure association tables — so the script stays safe to re-run against a database that already has
this demo data loaded.

# Phase 0 Research: Audit Trail for Topics, Groups, and Participants

The feature spec's own Clarifications session already resolved every open product question (edit-detail scope,
join-pair linkage, unbounded display), so there are no `NEEDS CLARIFICATION` markers in Technical Context. What
remains is how to fit "one shared audit table" and "record who did it, in what capacity" into a codebase where
**no mutating service method currently receives the acting user at all** — every organiser route today performs
its write with no notion of "who is calling this," and `GroupService`/`ParticipantService`/`TopicService` have
no such parameter anywhere. Each entry below follows Decision / Rationale / Alternatives considered.

## 1. How audit writes get triggered: explicit calls inside existing service methods, not AOP or an event bus

**Decision**: Every mutating method on `TopicService`, `GroupService`, and `ParticipantService` that the spec
requires to be audited (FR-001) gains an `AuditActor` parameter and, at the point the mutation is persisted,
makes an explicit `auditService.record(...)` call chained into the same reactive pipeline
(`.flatMap(...)`/`.then(...)`), reusing whatever `TransactionalOperator` that method already wraps itself in
(e.g., `GroupService.join`/`leave` already use one for the advisory-lock join logic).

**Rationale**: This codebase has no AOP infrastructure and no `ApplicationEventPublisher` usage anywhere
(confirmed by inspection of every `@Service` class) — introducing either would be the first of its kind and a
non-trivial new pattern to reconcile with Reactive-First (Spring's synchronous `ApplicationEventPublisher` is
not naturally awaitable inside a `Mono` chain, and a reactive event bus would be a new dependency the
constitution's "Spring Boot Native Only" principle disfavors without demonstrated need). Explicit calls also
give the strongest atomicity guarantee for SC-005 ("testers never observe one entry recorded without the
other"): both audit inserts for a Topic join happen inside the exact same `TransactionalOperator.transactional`
chain `GroupService.join` already wraps its Group-creation-or-growth logic in, so a failure after the mutation
but before the second audit write rolls back the whole thing, not just the mutation.

**Alternatives considered**: A Spring AOP `@Around` aspect matching mutating method names — rejected: it would
still need the acting user threaded to it via `ThreadLocal` (which does not survive Reactor's thread-hopping
scheduling) or a Reactor Context read (workable, but a materially more complex first-of-its-kind mechanism for
a codebase that has, so far, kept every cross-cutting concern explicit — see `CurrentUserModelAdvice`'s own
comment on why it avoided a Thymeleaf Security dialect in favor of a plain `@ControllerAdvice`). Firing audit
writes from `ApplicationEventPublisher` after the transaction commits — rejected: "after commit" is exactly the
window in which SC-005's atomicity guarantee would be lost, and WebFlux does not provide a reactive equivalent
of `@TransactionalEventListener` to close that gap.

## 2. Threading the acting user and capacity: a small `AuditActor` record, resolved per-request from the route, not the user's own privilege flag

**Decision**: Introduce `record AuditActor(UUID userId, boolean organiser)`. Each controller resolves it once
per request — `userId` from the already-present `@AuthenticationPrincipal HackathonOidcUser`, and `organiser`
**structurally**, from which controller/route handled the request: `true` for every route already living under
`/organiser/**` (`TopicController`, `GroupController`, `ParticipantController`), `false` for every self-service
route elsewhere (`TopicJoinController`, `RegistrationController`, `ProfileController`, the author's own
`PUT`-style Topic edit). The resolved `AuditActor` is passed as an explicit parameter into the service call,
exactly like `userId` is already passed into `TopicJoinService.join(topicId, requesterUserId)` today. On the
table itself, this becomes a single `organiser boolean NOT NULL` column (data-model.md) — `true` meaning the
action was taken in the Organiser capacity, `false` meaning standard-user/participant capacity; there is no
separate enum type for this in the database or in `AuditActor`.

**Rationale**: This directly implements the spec's own Assumption ("the capacity... reflects the capacity the
actor was in at the moment the action was performed... not the actor's general set of privileges") with a rule
that is unambiguous and needs zero new state: a person who happens to hold the Organiser privilege but joins a
Topic through their own Participant account (a standard-user-capacity, self-service route) is recorded with
`organiser = false` for that action, while the same person editing someone else's Participant record through
`/organiser/participants/{id}/status` is recorded with `organiser = true` — because those are two different
controllers today, not two branches of the same one. No lookup or flag is needed beyond "which controller is
this." A plain boolean is sufficient because the business concept genuinely is binary (Organiser capacity or
not) — there is no third capacity anywhere in this spec, so a `text`/enum column would just be a boolean wearing
extra clothing.

**Alternatives considered**: Deriving capacity from `HackathonOidcUser.getUser().isOrganiser()` directly (i.e.,
"Organiser privilege holder" always logs `organiser = true`) — rejected: this conflates *holding* the privilege
with *using* it for this specific action, which the spec's Assumption explicitly rules out, and would make
every self-service action taken by an Organiser-privileged Participant permanently misrecorded. Passing an
explicit `organiser` flag from the browser/form — rejected as attacker-controllable input for a
security-relevant field; the route itself is the only trustworthy signal. A `text`/enum `actor_capacity` column
(`ORGANISER`/`STANDARD_USER`) — this was the original design; superseded by a plain boolean per product
direction, since the concept has exactly two states and is never expected to grow a third.

## 3. `audit_entries` schema: a generic `(subject_type, subject_id)` pair, no foreign keys, plus a denormalized label

**Decision** (supersedes an earlier draft of this document that used two typed, nullable foreign keys instead —
see the note at the end of this section): One new table, `audit_entries`, with a single generic subject
reference — `subject_type text NOT NULL` (`TOPIC` or `PARTICIPANT`) and `subject_id uuid NOT NULL` — and **no
foreign key on that reference at all**. There is no `topic_id`, `group_id`, or `participant_id` column; every
row instead says *what kind* of thing it's about (`subject_type`) and *which one* (`subject_id`), and every
Group-affecting event is written with `subject_type = 'TOPIC'` and `subject_id` set to that Group's own
`topicId` (read off the `Group` row already in hand at the point of the write), never a separate Group
reference — the underlying product decision (Groups have no audit trail of their own) is unchanged, only the
column shape that expresses it. `actor_user_id` keeps its plain FK to `users` (unaffected — it is always
exactly one type, `User`, so a typed FK there is unambiguous and free of the polymorphism problem below). Every
row also carries a `subject_label` (text, `NOT NULL`, set once at write time — e.g. the Topic's name, the
Participant's display name) so the entry stays legible regardless of whether the thing `subject_id` points to
still exists.

**Rationale**: `ParticipantController#delete` performs a genuine `DELETE FROM participants`, unlike Topics,
which are never hard-deleted anywhere in this codebase. Under the earlier typed-FK design, that asymmetry meant
`participant_id` needed `ON DELETE SET NULL` while `topic_id` didn't — two different delete behaviors on what
was conceptually the same kind of column. A generic, FK-less `subject_id` sidesteps that asymmetry entirely:
deleting a Participant simply leaves old rows' `subject_id` pointing at an id that no longer exists in
`participants` — there is nothing to cascade, null out, or otherwise react to, because there is no constraint
declared in the first place. `subject_label` already carries everything a reader needs to make sense of the row
(FR-009), so the loss of a live FK costs nothing in practice: this feature never needs to join from
`audit_entries` back to `topics`/`participants` to render an entry, only to filter by `subject_type` +
`subject_id` when listing one record's history. This also collapses what was previously a two-nullable-column
invariant ("exactly one of `topic_id`/`participant_id` is set") into a single non-nullable pair that is simply
always present — `subject_type`/`subject_id` are `NOT NULL` on every row, no service-level "exactly one of N"
guard needed at all, and no awkward discussion of why a DB `CHECK` constraint was the wrong tool (the earlier
draft's ON-DELETE-vs-CHECK tension disappears along with the FK that caused it).

**Alternatives considered** (this section previously reached a different conclusion; recorded here for
context): Two or three typed, nullable foreign keys (`topic_id`, `group_id`/`participant_id`) — the original
design, chosen at the time for referential integrity and to avoid a type-discriminator branch in the read
queries. Superseded per explicit product direction: a single generic `(subject_type, subject_id)` pair is
simpler to extend if a future entity type is ever added (no new column, just a new `subject_type` value), avoids
the FK-vs-hard-delete asymmetry above, and the "no type-discriminator branch" benefit of typed columns was
smaller than it first appeared, since `findForTopic`/`findForParticipant` already need to be two distinct
methods regardless of column shape — trading a `WHERE subject_type = 'TOPIC' AND subject_id = :id` for `WHERE
topic_id = :id` is not a meaningful simplification once both are already parameterized per-type at the Java
call site.

## 4. The Topic-membership pair: a shared `action_id` column, not inferred from event type + timestamp

**Decision**: `audit_entries` gains a nullable `action_id` (uuid) column. `AuditService.record(...)` accepts an
optional pre-generated `UUID actionId`; when `GroupService.addMember`/`removeMember` fire their two
`JOINED`/`LEFT` entries (the Topic-side entry — `subject_type = 'TOPIC'`, `subject_id` = the Topic — and the
Participant-side entry — `subject_type = 'PARTICIPANT'`, `subject_id` = the Participant, FR-004) — whether
reached via the self-service `join`/`leave` or the organiser's direct add/remove-member route — both calls pass
the same freshly-generated `actionId`. Every other event type leaves it `null`.

**Rationale**: Directly implements the resolved clarification (a shared action identifier, not merely
independent-but-consistent entries) — the pairing becomes a stored, queryable fact ("show me the two entries for
`action_id = X`") rather than something a reader or test has to reconstruct by matching timestamps and event
types, which is fragile once two different Participants join the same Topic within the same second.

**Alternatives considered**: Reusing `occurred_at` equality as the implicit link — rejected by the
clarification itself, and fragile under concurrent joins to different Topics landing in the same instant.
Introducing a third `audit_actions` parent table that both entries FK into — rejected as premature: nothing in
the spec needs an `action_id` to carry its own attributes (no action-level metadata beyond "these rows go
together"), so a bare correlation column is the minimal structure that satisfies FR-004a.

## 5. Two-entity actions get a lock for both entities, not just one (FR-004, FR-004a; latent race found in `GroupService`)

**Decision**: Every `GroupService` method that touches both a Group/Topic and a Participant in one action —
`join`, `leave`, `addMember`, `removeMember` — acquires a Postgres session-scoped advisory lock keyed to
**each** entity it affects, not only the Topic. `join`/`leave` already acquire
`pg_advisory_xact_lock(hashtext('topic-join:' || topicId))`; they now *also* acquire
`pg_advisory_xact_lock(hashtext('participant-join:' || participantId))`, always in the fixed order **topic
lock first, then participant lock**, before doing anything else. `addMember`/`removeMember` acquire the
participant-scoped lock themselves (so the organiser's direct `/organiser/groups/{id}/members` add/remove
routes — which call them without going through `join`/`leave` at all — get the same protection), wrapped in
the existing shared `TransactionalOperator` bean; when already called from inside `join`/`leave`'s transaction,
Spring's reactive transaction management participates in that same transaction (default `REQUIRED`
propagation) rather than opening a nested one, so re-acquiring the same participant lock there is a safe,
cheap no-op, not a second lock.

**Rationale**: This closes a real, previously-undocumented race: a Participant joining Topic A and Topic B
concurrently acquires *two different* topic locks (keyed on different topic ids) and both requests proceed
into `addMember` completely unguarded by anything participant-scoped — the only backstop today is the
`group_members_participant_id_active_key` partial unique index, so the loser of that race gets a raw,
untranslated constraint-violation exception instead of the friendly `GroupConflictException` (e.g. "This
participant already belongs to a different active Group") every other invariant in this service already
produces. Locking on the Participant too closes the window entirely: the second request now blocks until the
first's transaction commits, then correctly observes the just-created membership and rejects cleanly. The
general principle — whenever one action affects two entities, both must be locked, not just one — generalizes
directly to this feature's own FR-004a requirement that a Topic join's two audit entries (Topic/Group side and
Participant side) are written atomically: the same transaction that now holds both locks is also the one
`AuditService.record(...)` is called twice inside (research.md §1), so the entity-level and audit-level
atomicity guarantees are backed by the identical lock scope.

**Lock ordering rationale (deadlock safety)**: Every code path that ever needs both locks acquires them in the
same fixed order — topic before participant — and no code path acquires them in the reverse order. A
fixed, universally-applied ordering makes a circular wait (the precondition for deadlock) structurally
impossible, the same reasoning already used to justify a *single* advisory lock per topic in 005's join design,
just extended to two locks instead of one.

**Alternatives considered**: Leaving the DB unique index as the sole backstop — rejected: it already exists
today and is exactly what produces the raw, un-translated exception this decision fixes; relying on it alone
is the status quo bug, not a fix. A single combined lock key hashing `(topicId, participantId)` together —
rejected: it would only serialize *that specific pair*, not the Participant against a *different* Topic, which
is precisely the cross-topic race that needs closing. Locking the Participant row itself with `SELECT ... FOR
UPDATE` instead of an advisory lock — rejected: the Participant row isn't otherwise read/written inside this
transaction (the check is against `group_members`, a different table), so a row lock would add an
unnecessary, easily-missed coupling to `participants` for a lock that is really about the `group_members`
invariant; the existing advisory-lock idiom (already used twice elsewhere in this codebase —
`ParticipantService`'s own `participant-registration-cap` lock, and 005's `topic-join` lock) is the established,
consistent tool for this class of problem here.

## 6. Immutability (FR-010): enforced by omission, not a trigger

**Decision**: `AuditEntryRepository` (a plain `ReactiveCrudRepository<AuditEntry, UUID>`) is used only for
`save` (insert) and the `findBy...` read methods `AuditService` exposes. No controller, service, or template
anywhere calls `.save()` with an existing id, `.delete...()`, or any `UPDATE`/`DELETE` SQL against
`audit_entries`. No database trigger or revoked `UPDATE`/`DELETE` grant is introduced.

**Rationale**: Every other "never changes after the fact" invariant in this codebase (e.g., a Topic's
`created_by_user_id`, per its own schema.sql comment: "retained even if that User's access is later revoked")
is already enforced the same way — by simply never writing code that touches it — not by a database-level
trigger or reduced grant. Matching that existing convention keeps this feature consistent with the rest of the
codebase rather than introducing the first database trigger or privilege restriction in the project.

**Alternatives considered**: A Postgres `BEFORE UPDATE OR DELETE` trigger raising an exception — rejected as
the first trigger in the project for an invariant every other "permanent" field already satisfies without one;
would also complicate the idempotent, plain-`;`-statement `schema.sql` runner style noted in that file's own
existing comments (no `$$`-quoted PL/pgSQL block support).

## 7. Access control (FR-005/FR-006): reuse `/organiser/**`, add zero new security code

**Decision**: The two new "Audit" GET routes are added directly onto `TopicController` and
`ParticipantController` — already mounted under `/organiser/topics` and `/organiser/participants` respectively
— so `SecurityConfig`'s existing `.pathMatchers("/organiser/**").hasRole("ORGANISER")` rule covers them with no
additional code. `GroupController` gains no route of its own (research.md §9); its detail page's "Audit" link
simply points at the Topic route, which is already covered the same way.

**Rationale**: This is the same reasoning `GroupController`'s own class-level Javadoc already states for every
route it owns ("Access to every route here is restricted to `ROLE_ORGANISER` by `SecurityConfig`'s
`/organiser/**` path rule"). Because the rule is enforced by the security filter chain before any controller
method runs, FR-006's requirement ("enforce... at the point audit data is retrieved, not only by hiding the
interface element") is satisfied structurally: there is no way to reach these handler methods without the role,
regardless of whether the "Audit" button/link was used to get there.

**Alternatives considered**: A new stand-alone `/organiser/audit/**` controller — rejected: it would still need
the same `/organiser/**` prefix to get the free security coverage, and would separate each route from the
entity-specific controller that already owns everything else about that entity's organiser view (its list,
detail, and mutation routes), breaking the codebase's established per-entity controller convention for no
benefit.

## 8. On-demand loading (FR-007/FR-008): a normal server-rendered GET route, not an AJAX/JS panel

**Decision**: The "Audit" action on the Topic and Participant detail pages is a plain Thymeleaf link/button
(`<a>`/`th:href`) to a new GET route (`/organiser/{topics|participants}/{id}/audit`) that renders its own full
server-rendered page (reusing the `organiser/fragments/layout` shell and a new shared `organiser/audit/list`
table fragment). The Group detail page's "Audit" link is the same kind of plain `th:href`, just pointed at
`/organiser/topics/{topicId}/audit` for its own Group's Topic (research.md §9) rather than a route of its own.
No audit data is included in the model of any of the three existing detail-page GET handlers.

**Rationale**: This is the only approach compatible with the constitution's Thymeleaf SSR principle
("Client-side rendering frameworks... MUST NOT be used") and trivially satisfies "no audit data is fetched or
displayed until requested" (Story 2, Acceptance Scenario 1) — the data simply isn't queried by the detail page's
own handler at all, not merely hidden by CSS/JS after being sent to the browser.

**Alternatives considered**: An inline collapsible `<details>` panel on the same detail page, populated via a
`hx-get`-style partial fetch — rejected: this project has no HTMX/Alpine/similar library on its dependency list
(only Pico CSS, per the constitution), and introducing one for a single feature would be a new frontend
dependency with no demonstrated need over a second page.

## 9. The Group detail page's "Audit" action reuses the Topic route, rather than getting one of its own

**Decision**: `organiser/groups/detail.html` gets an "Audit" link whose `th:href` points directly at
`/organiser/topics/{topicId}/audit` (`GroupDetail.group().getTopicId()`, already available on that page's
existing model — `GroupService.findDetail` already loads the `Group` row). `GroupController` gains no new
`/organiser/groups/{id}/audit` route, no delegation method, and no redirect — the link's target *is* the Topic
route, directly, from the very first render of the Group detail page.

**Rationale**: This is the most literal possible implementation of the resolved product decision ("Groups do
not have an audit trail of their own... the Group detail view's 'Audit' action... opens that Topic's audit
trail rather than a separate Group-scoped one"). A `Group`'s `topicId` is a stable, always-populated field for
the entire lifetime of that row (set once at creation, per `data-model.md`'s "Modified Entities" from 005, never
reassigned) — there is no historical-Group-with-unknown-Topic case to handle, unlike the Participant-deletion
edge case in research.md §3. A direct link is therefore strictly simpler than any server-side indirection and
costs nothing in either flexibility or correctness.

**Alternatives considered**: A `GET /organiser/groups/{id}/audit` route on `GroupController` that itself looks
up the Group's `topicId` and issues a redirect to `/organiser/topics/{topicId}/audit` — rejected as an
unnecessary extra hop (one more route to secure, test, and reason about) for a value the detail page's own
template already has in hand; a plain link is strictly less code for the identical end-user result. Rendering
the Topic's audit table inline as a fragment embedded in the Group detail page itself (rather than navigating
to the Topic's own page) — rejected: it would violate FR-007/Story 2's "no audit data until explicitly
requested" rule for the Group detail page's initial load, and duplicates rendering logic the Topic's audit page
already owns.

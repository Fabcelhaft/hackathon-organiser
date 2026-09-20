# Feature Specification: Event-Driven Task Rules

**Feature Branch**: `011-event-driven-task-rules`

**Created**: 2026-09-19

**Status**: Draft

**Input**: User description: "besides kafka and websocket another event handler should be added. This enables creation of tasks for different Events. The tasks can have wildcards from the event JSON in a certain path. The tasks are also listed in the organizer view. just a table with title, assignee, save button and done button. Filtering per default only undone tasks. propose me how this could be integrated in the UI to configure it."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Turn Events into Tasks Automatically (Priority: P1)

An Organiser wants the system to remind the team to do something whenever a particular thing
happens in the hackathon. For example: every time a Topic is proposed, somebody has to review it.
Today the Organiser either watches the Topics page or wires up an external system through Kafka or
a webhook. Instead, the Organiser configures a Task Rule: pick which Event Types it reacts to,
write a title pattern such as `Review new topic: {{topic.name}}`, and enable it. From then on,
every matching Event produces a Task whose title has the real values filled in from that Event's
data, and the Task shows up on the Organiser's Task list.

**Why this priority**: This is the feature's core value and the only part that cannot be simulated
by hand. Without it there is nothing to list, assign, or complete. On its own it already delivers
value: a visible, automatically-maintained to-do list derived from what actually happened.

**Independent Test**: Configure one Task Rule for a single Event Type with a title pattern
containing at least one wildcard, trigger that Event Type through normal use of the application,
and confirm a Task appears on the Task list with the wildcard replaced by the Event's real value.

**Acceptance Scenarios**:

1. **Given** an enabled Task Rule subscribed to "Topic proposed" with the title pattern
   `Review new topic: {{topic.name}}`, **When** a participant proposes a Topic named "Robot Arm",
   **Then** a new undone Task titled "Review new topic: Robot Arm" appears on the Task list.
2. **Given** the same Task Rule but disabled, **When** a Topic is proposed, **Then** no Task is
   created.
3. **Given** an enabled Task Rule subscribed to two Event Types, **When** either Event Type occurs,
   **Then** one Task is created per occurrence.
4. **Given** an enabled Task Rule that already produced a Task titled "Review new topic: Robot Arm",
   **When** an Event produces that exact same title again, **Then** a second, separate Task is
   created and both appear on the list.
5. **Given** a title pattern referencing a path that does not exist in that Event's data, **When**
   the Event occurs, **Then** a Task is still created and the unresolved wildcard contributes
   nothing to the title.
6. **Given** an Event that matches no enabled Task Rule, **When** it occurs, **Then** no Task is
   created and the triggering action completes normally.
7. **Given** a Task Rule that fails while creating a Task, **When** the Event occurs, **Then** the
   action that triggered the Event still succeeds and the failure is recorded in the application
   log without any user-facing error.
8. **Given** a Task Rule that has created 40 undone Tasks and 5 done ones, **When** the Organiser
   deletes that Rule, **Then** they are told 40 undone Tasks will be removed, and on confirming, the
   40 disappear from the list while the 5 done ones remain.
9. **Given** the same Task Rule, **When** the Organiser disables it instead of deleting it, **Then**
   all 45 Tasks remain on the list and only the creation of new ones stops.

---

### User Story 2 - Assign and Complete Tasks (Priority: P2)

An Organiser opens the Task list, sees the outstanding work, picks who should handle each item, and
ticks items off as they get done. Each row offers an assignee control and a Save button to record a
change to that row, plus a Done button that marks the Task complete.

**Why this priority**: Turns a passive list of generated items into a workflow. Independently
valuable once Tasks exist, but meaningless without User Story 1 producing them.

**Independent Test**: With Tasks present on the list, change one row's assignee, press Save, reload
the page, and confirm the assignee persisted; then press Done on a row and confirm that Task is
recorded as done.

**Acceptance Scenarios**:

1. **Given** an unassigned Task on the list, **When** the Organiser selects an assignee from the
   Organisers offered and presses Save on that row, **Then** the Task shows that assignee after the
   page reloads.
2. **Given** an assigned Task, **When** the Organiser clears the assignee and presses Save, **Then**
   the Task becomes unassigned.
3. **Given** an undone Task, **When** the Organiser presses Done, **Then** the Task is marked done
   together with the moment it was completed, and it leaves the default list view.
4. **Given** a done Task shown in a view that includes done Tasks, **When** the Organiser reopens
   it, **Then** it becomes undone again and returns to the default list view.
5. **Given** two Organisers with the same list open, **When** one presses Done and then the other
   presses Done on the same row, **Then** the second press does not produce an error and the Task
   stays done.

---

### User Story 3 - Focus on Outstanding Work (Priority: P3)

The Task list grows over the course of a hackathon. An Organiser opening it should see only what
still needs doing, without having to scroll past everything already completed, while still being
able to look back at finished work when needed.

**Why this priority**: A usability refinement on top of User Stories 1 and 2. The list is usable
without it for a short event, but becomes unusable at volume.

**Independent Test**: With both done and undone Tasks present, open the Task list with no filter
chosen and confirm only undone Tasks are listed; then switch the filter to include done Tasks and
confirm they appear.

**Acceptance Scenarios**:

1. **Given** a mix of done and undone Tasks, **When** the Organiser opens the Task list without
   choosing a filter, **Then** only undone Tasks are listed.
2. **Given** the Organiser has switched the filter to show done Tasks, **When** the page is
   displayed, **Then** done Tasks are listed and each shows when it was completed beneath its title.
3. **Given** two Tasks with identical titles, one created before the other, **When** either view
   lists them, **Then** the text beneath each title lets the Organiser tell them apart and see which
   came first.
4. **Given** every Task is done, **When** the Organiser opens the default list view, **Then** an
   explicit empty-state message is shown rather than a bare empty table.

---

### Edge Cases

- **Wildcard path missing from the Event**: The Task is still created; the wildcard resolves to
  nothing. This is expected whenever one Rule is subscribed to several Event Types whose data
  differs.
- **Wildcard resolves to a structure rather than a single value** (for example a whole object or a
  list): treated the same as a missing path — it contributes nothing to the title.
- **Wildcard resolves to an empty value** (a participant with no email address, a topic with no
  description): treated the same as a missing path — it contributes nothing, and the title reads as
  though the wildcard were not there. The word "null" never appears in a title.
- **Wildcard resolves to a timestamp or an identifier**: written in full as it appears in the Event
  data, unconverted. A pattern pointing at one of these produces a long, machine-shaped title, which
  is a signal the pattern should point at a name instead.
- **Every wildcard in the pattern is unresolved**, leaving a blank or whitespace-only title: the
  Task is created with a fallback title naming the Rule and the Event Type, so no Task is ever
  nameless.
- **A wildcard substitutes a very large value** (for example `{{topic.description}}` pulling in
  several paragraphs): the resolved title is shortened to 500 characters with a visible ellipsis and
  the Task is still created. A clipped title is a signal the pattern points at the wrong field.
- **A Rule is disabled** while Tasks it created are still open: those Tasks remain on the list and
  stay workable. Disabling only stops new Tasks being created.
- **A Rule is deleted** while Tasks it created are still open: its undone Tasks go with it, its done
  Tasks stay as a record. This is the escape hatch for a Rule that generated a large number of
  unwanted Tasks — the Organiser deletes the Rule rather than closing each Task by hand.
- **The assigned User loses Organiser rights or is removed**: the Task remains and is treated as
  unassigned rather than disappearing or blocking the list. The former assignee is no longer offered
  in the assignee control.
- **The same Event Type fires repeatedly** (a Topic proposed, withdrawn, and proposed again): each
  occurrence produces its own Task, even where the resolved titles are identical. The Organiser
  closes them individually.
- **A Rule's default assignee later stops being an Organiser**: Tasks created from then on are
  unassigned; the Rule continues to run without error.
- **A participant quoted in a Task title is later deleted**: the Task and its title survive
  unchanged. A title is plain text frozen at creation (FR-013) with no link back to the person, so
  deleting the participant does not reach it. This is deliberate and matches the audit trail, which
  already snapshots a label before deleting a participant so the record outlives them.
- **A Task Rule is enabled with no Event Types selected**: the save is accepted — this matches the
  existing handler types — and the Rule never fires. The handler list marks it as inert so the
  Organiser is not left wondering why no Tasks appear.
- **A Task Rule is saved with a malformed wildcard** (unclosed braces, empty path): the save is
  rejected with a message naming the problem, rather than being accepted and failing silently at
  Event time.
- **Two Organisers edit the same Task simultaneously**: the later Save wins for the assignee; a
  Done that arrives after the Task is already done is accepted without error.

## Requirements *(mandatory)*

### Functional Requirements

#### Task Rule configuration

- **FR-001**: The system MUST offer a third kind of Event handler alongside the existing Kafka and
  HTTP POST handlers, which creates a Task inside this application instead of sending the Event to
  an external system.
- **FR-002**: Organisers MUST be able to create, edit, enable, disable, and delete Task Rules.
- **FR-002a**: Deleting a Task Rule MUST also remove every undone Task that Rule created, and MUST
  keep every done one as a record of work actually carried out. The confirmation MUST state how many
  undone Tasks will be removed before the Organiser commits. Disabling a Rule MUST NOT remove any
  Task — this is the difference between the two actions, and it is how an Organiser recovers from a
  badly-written Rule that generated a large number of unwanted Tasks.
- **FR-003**: A Task Rule MUST carry a name that is unique among all Event handlers, an
  enabled/disabled state, a selection of zero or more Event Types it reacts to, and a task title
  pattern. Selecting no Event Types MUST be permitted, matching the existing handler types; such a
  Rule simply never fires, and FR-032a makes that visible.
- **FR-004**: A Task Rule MUST be selectable against the same catalog of Event Types already
  available to the existing Event handlers, with no Event Type excluded.
- **FR-005**: A Task Rule MUST support an optional default assignee, chosen from the Users holding
  the Organiser role, applied to every Task it creates. Leaving it blank MUST be permitted and MUST
  produce unassigned Tasks.
- **FR-006**: A newly created Task Rule MUST start disabled, matching how the existing Event
  handlers behave, so that a half-configured Rule cannot generate Tasks.
- **FR-007**: The system MUST reject saving a Task Rule whose title pattern is empty, or whose
  pattern contains a wildcard that is unclosed, has an empty path, or has an empty path segment
  (a leading, trailing, or doubled dot). The rejection MUST name the offending wildcard and MUST NOT
  discard what the Organiser typed.
- **FR-008**: The configuration screen MUST document the wildcard syntax of FR-009 and FR-010 and
  show at least one worked example, so an Organiser can write a pattern without reading the source
  data format.

#### Wildcard resolution

- **FR-009**: A task title pattern MUST mark a wildcard by wrapping its path in double curly braces
  — `{{path}}`. Text outside a wildcard MUST be treated as literal title text, including a single
  curly brace.
- **FR-010**: A wildcard's path MUST be a sequence of segments separated by a single dot, addressing
  values nested at any depth within the Event's data. A segment that is a whole number MUST select
  that position within a list, counting from zero — for example `{{topic.name}}` and
  `{{customFields.0.definition.label}}`.
- **FR-011**: When an Event occurs, the system MUST produce the Task title by replacing every
  wildcard in the pattern with the corresponding value from that Event, leaving all surrounding
  literal text untouched.
- **FR-011a**: A wildcard resolving to a single value MUST write that value exactly as it appears in
  the Event's data — text unchanged, a number as written, a true/false flag as `true` or `false`, a
  timestamp as its ISO-8601 text, an identifier in full. No reformatting, localisation, or
  timezone conversion is applied during title generation.
- **FR-012**: A wildcard MUST resolve to nothing, and MUST NOT prevent the Task from being created,
  in each of these cases: its path is absent from the Event, its path addresses a structure rather
  than a single value, or the value it addresses is empty. These three cases MUST be
  indistinguishable in the resulting title — there is exactly one way a wildcard comes up blank.
- **FR-013**: The title recorded on a Task MUST be the resolved text, fixed at the moment of
  creation; later changes to the Rule's pattern MUST NOT alter Tasks already created.
- **FR-014**: If the resolved title is empty or whitespace only, the system MUST substitute a
  fallback title identifying the originating Rule and Event Type.
- **FR-015**: A resolved title longer than 500 characters MUST be shortened to 500 characters,
  ending with an ellipsis that makes the shortening visible, rather than causing the Task creation
  to fail. The 500-character limit applies to the resolved title, never to the pattern itself.

#### Task creation

- **FR-016**: When an Event occurs, the system MUST create one Task for every enabled Task Rule
  subscribed to that Event Type, on every occurrence. The system MUST NOT suppress, merge, or
  de-duplicate a Task because an earlier Event produced a similar or identical one.
- **FR-017**: A created Task MUST record its title, its assignee (possibly none), whether it is
  done, which Event Type produced it, which Rule produced it, and when it was created.
- **FR-018**: Task creation MUST NOT delay, block, or fail the user action that triggered the Event;
  a failure to create a Task MUST be recorded in the application log and MUST NOT surface as an
  error to the person whose action triggered it.
- **FR-019**: Disabled Task Rules MUST NOT create Tasks.

#### Task list

- **FR-020**: The Organiser area MUST provide a Task list reachable from the Organiser navigation
  via exactly one new navigation entry. Task Rule configuration MUST NOT add a navigation entry of
  its own — it lives on the existing Event handler configuration screens (FR-032, FR-033).
- **FR-021**: The Task list MUST be a table of exactly four columns, one row per Task: the Task's
  title, an assignee control offering the Users holding the Organiser role plus an explicit
  "unassigned" choice, a Save action, and a Done action.
- **FR-021a**: Beneath each Task's title, and within the same column, the row MUST show when that
  Task was created, and — on a done Task — when it was completed. This secondary text MUST make two
  Tasks bearing identical titles distinguishable from one another and orderable by eye. No further
  column may be added to satisfy this.
- **FR-022**: Opening the Task list without choosing a filter MUST show only undone Tasks.
- **FR-023**: Organisers MUST be able to switch the list to include done Tasks, and the chosen
  filter MUST survive actions taken from the list (saving, completing, reopening).
- **FR-024**: Saving a row MUST persist that row's assignee and leave every other Task untouched.
- **FR-025**: Marking a Task done MUST record that it is done and when, and MUST remove it from the
  default list view.
- **FR-026**: Organisers MUST be able to reopen a done Task from a view that includes done Tasks.
- **FR-027**: Marking a Task done that is already done, or reopening one that is already undone,
  MUST be accepted without an error.
- **FR-028**: The list MUST be ordered so that the most recently created Task appears first.
- **FR-028a**: The default undone view MUST show every undone Task with no cap, since Organisers
  empty it as they work. A view that includes done Tasks MUST show only the 200 most recently
  created done Tasks, and MUST say so where the list is cut off so an Organiser is never misled into
  thinking older done Tasks no longer exist. Undone Tasks MUST never be withheld from any view.
- **FR-029**: When no Task matches the current filter, the list MUST show an explanatory empty
  state.
- **FR-030**: The Task list and every action on it MUST be restricted to Organisers.
- **FR-031**: The Task list and the Task Rule configuration screens MUST be operable by keyboard
  alone and MUST expose each row's controls with labels that identify which Task they act on, so
  that a list of similarly-shaped rows remains unambiguous to assistive technology.

#### Placement of the configuration UI

- **FR-032**: Task Rules MUST appear in the same list as the existing Kafka and HTTP POST handlers,
  identified by their handler type, with the same enable, disable, edit, and delete actions
  available to them as to the other types.
- **FR-032a**: The handler list MUST mark any *enabled* handler that has no Event Types selected as
  inert, so an Organiser can see at a glance that it will never do anything. This applies to all
  three handler types, not only Task Rules — a Kafka or HTTP POST destination in the same state is
  equally inert and equally worth flagging. A disabled handler MUST NOT be flagged, since being idle
  is already its stated condition.
- **FR-033**: Task Rules MUST be configured through the same form as the existing handler types,
  with the Task-specific inputs revealed by selecting the Task handler type and the inputs belonging
  to the other types hidden. Inputs that have no meaning for a Task Rule MUST NOT be shown.
- **FR-034**: The handler name, enabled state, and Event Type selection MUST behave identically
  across all three handler types, with no Task-specific variation. In particular, saving with no
  Event Types selected MUST be accepted or rejected the same way for all three.

### Key Entities

- **Task Rule**: An Organiser-configured Event handler that creates Tasks instead of dispatching
  Events outward. Holds a unique name, an enabled flag, the set of Event Types it reacts to, the
  task title pattern, and an optional default assignee. Sits beside the existing Kafka and HTTP POST
  handlers in the same configuration model.
- **Task**: A single unit of work produced by a Task Rule when an Event occurred. Holds the resolved
  title, an optional assignee, a done flag with its completion moment, the Event Type and Rule it
  came from, and its creation moment. Its content is fixed at creation and is unaffected by later
  edits to the Rule; the one remaining tie to its Rule is deletion, which takes undone Tasks with it
  (FR-002a).
- **Event Type**: The existing catalog of domain occurrences the system already publishes. Reused
  unchanged; this feature adds no new Event Types.
- **Assignee**: A User holding the Organiser role who can be held responsible for a Task. Referenced,
  never copied, so display name changes are reflected automatically. A Task may have none.

## UI Integration

This section answers the "propose me how this could be integrated in the UI" part of the request.
The approach below was confirmed as Option A in Clarification Q1 and is binding — FR-020 and
FR-032–FR-034 state it as requirements.

### Extend the existing Event Destinations screen, add one new navigation entry

The Organiser navigation gains exactly one new entry, **Tasks**. Rule configuration reuses the
screens that already exist for Kafka and HTTP POST handlers, because a Task Rule answers the same
question those screens already answer — "when this Event happens, what should happen next?"

**1. Event Destinations list** — an extra row type. The existing table already has Name, Type,
Connection, Status, and Subscribed Event Types columns. A Task Rule appears as a row whose Type
reads "Task" and whose Connection cell shows the title pattern instead of a broker or URL. Enable,
Disable, Edit, and Delete work exactly as they do for the other two types. An Organiser sees every
reaction to every Event in one table.

**2. Event Destination form** — a third option in the Type dropdown. Choosing "Task" hides the Kafka
and HTTP field groups and reveals a Task group, the same show/hide behaviour the form already uses
when switching between Kafka and HTTP POST:

- *Task title pattern* — a text field, with the wildcard syntax and a worked example shown directly
  beneath it as help text.
- *Default assignee* — an optional picker listing the Users who hold the Organiser role, blank by
  default.

The Name field, the Enabled state, and the Event Types checkbox group are shared with the other
types and need no change. The Credential field is hidden for Task Rules, as it has no meaning.

**3. Tasks page** — the new navigation entry, a single four-column table:

| Title | Assignee | | |
|---|---|---|---|
| Review new topic: Robot Arm<br>*created 14:02 today* | *(assignee picker)* | **Save** | **Done** |
| Review new topic: Robot Arm<br>*created 09:17 today* | *(assignee picker)* | **Save** | **Done** |

The second line under each title is what tells two identically-titled Tasks apart — an unavoidable
consequence of creating one Task per Event occurrence. On a done row it also carries the completion
time. No fifth column is added for either.

Above the table sits the filter: undone Tasks only by default, with a control to include done ones.
In a view that includes done Tasks, the Done button on an already-done row is replaced by Reopen,
and that view lists only the 200 most recently created done Tasks, saying so where it cuts off.

**Why this shape**: it adds one navigation entry rather than two; it keeps all Event-to-reaction
configuration on one screen instead of splitting it across two places an Organiser has to know to
check; and it reuses the Event Type selection and enable/disable behaviour wholesale rather than
building a parallel version of them.

**Trade-off accepted**: one Rule subscribed to several Event Types shares a single title pattern,
and wildcards valid for one Event Type will usually be absent from another. The intended practice is
one Rule per Event Type; FR-012 makes the mixed case degrade gracefully rather than fail.

**Alternatives rejected** (Clarification Q1): a fully separate "Task Rules" page, which would
duplicate the Event Type selection UI and split "what reacts to this Event" across two screens; and
folding rule management into a collapsible section of the Tasks page, which would bury configuration
under an operational list.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An Organiser who has never configured a Task Rule before can create a working one —
  choosing Event Types, writing a title pattern with at least one wildcard, and enabling it — in
  under 3 minutes, without consulting documentation outside the screen itself.
- **SC-002**: 100% of occurrences of a subscribed Event Type while a Rule is enabled produce exactly
  one Task from that Rule — no silent misses and no suppressed duplicates.
- **SC-003**: The action that triggers an Event completes in the same time whether or not Task Rules
  are configured; a Task Rule that cannot create its Task never causes a user-visible error.
- **SC-004**: Opening the Task list shows outstanding work immediately, with no filter interaction
  needed, in 100% of first visits.
- **SC-005**: An Organiser can assign and complete a Task in no more than two interactions per Task
  from the list view, with no intermediate page.
- **SC-006**: A Task list remains usable as a hackathon's Task count grows into the thousands — the
  default undone view loads and responds to Save and Done as quickly with 500 outstanding Tasks as
  with 10, and the done-inclusive view stays equally responsive regardless of how many done Tasks
  have accumulated behind it.
- **SC-007**: 100% of titles generated from patterns whose wildcards match the Event's data contain
  the Event's real values with no leftover wildcard markers visible to the Organiser.
- **SC-008**: Every Task list and Task Rule screen passes automated accessibility checks at the same
  level the project's existing Organiser screens are held to.

## Assumptions

- **The two existing Event handlers this feature joins are Kafka and HTTP POST.** The request's
  "kafka and websocket" meant Kafka and the HTTP POST (webhook) handler — confirmed by the requester
  on 2026-09-19. There is no WebSocket transport in the system, none is added here, and none is
  needed: this feature adds exactly one new handler type beside the existing two.
- Event data available to wildcards is the Event's published form — the same content the existing
  Kafka and HTTP POST handlers already send — so no new Event content needs to be produced for this
  feature.
- The Event Type catalog is unchanged. This feature adds no Event Types and modifies none.
- Tasks are created by Events only. There is no manual "create Task" action; adding one is out of
  scope.
- A Task's editable content is its assignee and its done state. Editing a Task's title, adding a
  description, due date, priority, or comments is out of scope.
- Task Rules, like the existing handlers, are configured by Organisers only and are global to the
  hackathon — there is no per-Topic or per-Group scoping.
- No notification is sent when a Task is created or assigned. Organisers discover Tasks by visiting
  the Task list.
- Tasks are retained for the lifetime of the hackathon data; no automatic archival or purge. The
  only way a Task is removed is deleting the Rule that created it, which takes that Rule's undone
  Tasks with it (FR-002a). Tasks cannot be deleted one at a time.
- Changes to Tasks are not written to the audit trail. Extending the existing audit trail to cover
  Tasks is out of scope.
- A Task title may quote personal data — a participant's email address, a custom-field answer —
  because a wildcard can reference any path in the Event. This is accepted deliberately: the title
  is a historical record of what happened, visible only to Organisers who could already see that
  data, and it outlives deletion of the participant it quotes, exactly as the audit trail's existing
  label snapshots do. No path is blocked and no warning is shown; an Organiser who does not want
  personal data in titles writes a pattern that does not reference it. Deleting the Rule remains the
  way to clear its unwanted output in bulk (FR-002a).
- Existing authentication and Organiser authorisation are reused unchanged.
- This feature makes exactly one change affecting the existing Kafka and HTTP POST handlers: the
  inert-handler marker of FR-032a, which applies to all three types. Everything else about those two
  handlers — their fields, validation, delivery behaviour, and stored data — is untouched.

## Clarifications

### Session 2026-09-19

The answers below are reflected in the requirements above; nothing in this spec is left pending.

#### Specification questions

- **Q1 — Where are Task Rules configured, relative to the existing Kafka and HTTP POST handlers?**
  **A: Option A.** Extend the existing Event handler list and form with a third "Task" type, and add
  exactly one new navigation entry for the Task list. Rejected: a separate "Task Rules" page (two
  nav entries, duplicated Event Type selection UI, configuration split across two screens) and a
  collapsible rules section inside the Tasks page (configuration buried under an operational list).
  Recorded in FR-020, FR-032, FR-033, FR-034, and the UI Integration section.

- **Q2 — Who can be assigned to a Task?**
  **A: Option A.** A User holding the Organiser role, chosen from a dropdown, or nobody. Rejected:
  any User (a participant cannot see the Task list, so the assignment would be only a note) and free
  text (no validation, inconsistent spelling, no reliable filtering later). Recorded in FR-005,
  FR-021, the Assignee entity, and the assignee-loses-Organiser-rights edge cases.

- **Q3 — Should a repeated Event create another Task?**
  **A: Option A.** Always create one Task per Event occurrence, with no de-duplication, merging, or
  suppression. The Task list is a faithful record of what happened; repeated Events produce repeated
  Tasks the Organiser closes individually. Rejected: skipping when a same-titled undone Task exists
  (silently swallows genuine second occurrences) and a per-Rule setting (an extra field and an extra
  decision for no demonstrated need). Recorded in FR-016, SC-002, User Story 1 scenario 4, and the
  repeated-Event edge case.

#### Clarification questions

- Q: What exact syntax should a task title pattern use to mark a wildcard and point at a value
  inside the event data? → A: `{{dotted.path}}`, with a whole-number segment selecting a list
  position counting from zero — `{{topic.name}}`, `{{customFields.0.definition.label}}`. Rejected:
  bracket indexing (a second notation with its own parsing and error cases), `${...}` (less
  distinctive than double braces), and full JSONPath (far more surface to validate and explain than
  the feature needs). Recorded in FR-007, FR-008, FR-009, FR-010.
- Q: How long is a Task title allowed to get before the system shortens it? → A: 500 characters,
  ending with a visible ellipsis. A deliberate product cap, not a storage one — this project stores
  every string as unbounded text — chosen so a sensible pattern never clips while a runaway
  substitution cannot wreck the list. Recorded in FR-015 and the large-value edge case.
- Q: When the Task list holds far more rows than fit comfortably on a page, what should it show? →
  A: No cap on the default undone view; a done-inclusive view shows only the 200 most recently
  created done Tasks and says where it is cut off. Targets the only view that grows without bound —
  Tasks accrue one per Event occurrence with no de-duplication, unlike every other Organiser list,
  which is bounded by real entities — without introducing pagination, which exists nowhere in this
  project. Rejected: no cap anywhere (the done view degrades over a long hackathon), pagination on
  both views (new machinery for a problem only one view has), and a warning without a cap (informs
  but does not solve). Recorded in FR-028a and SC-006.
- Q: Since two Tasks can now carry exactly the same title, what should each row show beyond the four
  things requested? → A: Keep exactly four columns; show the created time — and, on a done Task, the
  completion time — as secondary text beneath the title. Honours the original "just a table with
  title, assignee, save button and done button" while resolving two requirements that pulled against
  it: User Story 3 needs a completion time, and no-de-duplication means identical titles must still
  be distinguishable. Rejected: a fifth "Created" column and a sixth "Event Type" column (both widen
  the table past what was asked for), and dropping the completion time (leaves identical rows
  indistinguishable). Recorded in FR-021, FR-021a, User Story 3 scenarios 2 and 3, and the UI
  Integration section.
- Q: If an Organiser enables a badly-written Rule that generates hundreds of junk Tasks, how do they
  clear them? → A: Deleting the Rule also removes the undone Tasks it created, keeping the done ones
  as a record; disabling still leaves every Task alone. Reuses an action that already exists instead
  of adding a control, and keeps the four-column table untouched. Rejected: no removal at all
  (leaves the Organiser closing hundreds of Tasks by hand), a per-row Delete (a fifth control on a
  deliberately minimal row), and a bulk mark-as-done (clears the view but records junk as real
  work). Note this reverses the previous behaviour for deletion only. Recorded in FR-002a, the Task
  entity, the Rule-deleted and Rule-disabled edge cases, User Story 1 scenarios 8 and 9, and
  Assumptions.
- Q: When a wildcard points at a value that is not plain text — a true/false flag, a number, a
  timestamp, an id, or an empty value — what should appear in the title? → A: Write it exactly as it
  appears in the Event data, unformatted and unconverted; an empty value behaves like a missing path
  and contributes nothing. One rule instead of a table of special cases, and one way for a wildcard
  to come up blank rather than two. Rejected: writing the literal word `null` (noise in a title),
  reformatting timestamps to a local format (drags timezone and locale decisions into title
  generation), and rendering only text (silently drops legitimate values). Recorded in FR-011a,
  FR-012, and three Edge Cases.
- Q: Should a Task Rule be allowed to have no Event Types ticked, given the existing Kafka and HTTP
  POST handlers already allow exactly that? → A: Yes — allow none, matching the existing handlers,
  and flag any *enabled* handler with no Event Types as inert on the list, for all three types.
  Resolves a contradiction between FR-003 ("one or more") and FR-034 ("behave identically"), where
  the shipped code accepts none. Fixes the underlying hazard — an enabled handler that silently does
  nothing — without making a shared form validate differently by type. Rejected: requiring one for
  Task Rules only (divergence on a shared form), requiring one for all three (tightens validation on
  a shipped feature and could reject existing saved destinations), and relaxing FR-003 with no
  signal (leaves the inert state discoverable only by noticing "None selected"). Recorded in FR-003,
  FR-032a, FR-034, and a new Edge Case. This is the one deliberate scope expansion beyond the new
  handler type — noted in Assumptions.
- Q: A Task title is frozen when created and can quote a participant's email or custom-field answers
  — what should happen to it when that participant is later deleted? → A: The title survives
  unchanged; accept and document it, adding no new mechanism. Matches this project's existing
  deliberate behaviour, where the audit trail snapshots a label before deleting a participant so the
  record outlives them; titles are visible only to Organisers who could already see that data.
  Rejected: deleting Tasks on participant deletion (a Task does not record whose Event produced it,
  so this would need new data), blocking personal-data paths (rules out legitimate patterns such as
  "Contact {{user.email}}"), and warning on the form (a nudge, not a guarantee). Recorded in
  Assumptions and a new Edge Case.

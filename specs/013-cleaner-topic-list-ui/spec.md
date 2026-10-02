# Feature Specification: Cleaner Topic List UI

**Feature Branch**: `013-cleaner-topic-list-ui`

**Created**: 2026-10-01

**Status**: Draft

**Input**: User description: "dashboard and topic overview buttons are looking very unwanted. make a cleaner UI. Also remove skills from the homepage, so it isn't as small. if unsure how to solve ask questions"

## Context

Two participant-facing screens list Topics in a table: the Dashboard (home page) and the Topics
overview page. Each row currently ends in a cluster of full-size buttons — an "Upvote" button, a
"Join" button where applicable, and a "View" button — laid out as a wrapping row inside narrow
table cells. In practice the cells are too narrow to hold them side by side, so the buttons stack
vertically. The result, visible in the reported screenshots, is that every row grows to three or
four lines tall, the vote count and the buttons appear to belong to the same column, and the
boundary between the "Upvotes" column and the unlabelled action column disappears.

On the Dashboard the problem is compounded: the Topics card occupies roughly half the page width
and still carries a "Your Skills" column that is empty for most rows, squeezing the Topic name
into a narrow strip while contributing almost nothing.

This feature is a presentation change. No Topic, upvote, membership or skill data changes, and no
existing capability is withdrawn — every action a participant can perform today remains
performable afterwards.

## Clarifications

### Session 2026-10-01

- Q: At which viewport widths must the "every Topic row stays on one line" guarantee hold? (SC-001, FR-013) → A: Desktop and wider (>=1024px); below that the table may scroll horizontally rather than stack controls.
- Q: Should the Topic detail page's upvote control also change, given it renders the same action as a labelled button? (FR-021) → A: No. The detail page is unchanged; the compact vote control is a list-density fix and a single-Topic page keeps its labelled button.
- Q: When a participant activates the compact vote control, should the page reload as today or update in place? (FR-006, FR-010) → A: Update the count in place without a reload, as a progressive enhancement layered over the same form, which remains the fallback when scripting is unavailable.
- Q: Once the Dashboard stops showing the "Your Skills" column, should it also stop computing the viewer's matching skills? (FR-015) → A: Yes. Remove the matching-skills field from the Dashboard's row data and the query behind it, after confirming nothing else reads it.
- Q: How should a screen reader user learn their vote registered and what the new count is, now the page does not reload? (FR-006a, FR-008, SC-006) → A: Both — the control is a toggle whose pressed state flips, plus a polite announcement region that speaks the new count.

- Q: Should the count change the instant a participant clicks, or only once the server confirms? (FR-006a, FR-006c, FR-006d) → A: Immediately (optimistically), then reconcile with the server's answer and revert if it failed.

- Q: When a vote fails and the count snaps back, where is the participant told why? (FR-006c) → A: A brief notice next to the control that failed, overlaying rather than inserting so the row stays one line.

- Q: Should the Join control also be brought into the new UI, given the vote control is changing? (FR-011) → A: Yes, partly — Join gains the same allow-listed return-to-screen behaviour the upvote routes already have, and matches the compact control's visual weight. It stays a synchronous full-page action by design.

- Q: Should the Topics overview scroll horizontally when its content does not fit? (FR-012, FR-013) → A: No. Long Topic names wrap instead, so every column stays visible. Horizontal scrolling hid the right-hand columns, which is worse than a two-line name. Raised after seeing the deployed build.


## User Scenarios & Testing *(mandatory)*

### User Story 1 - Scan the Topic list without visual noise (Priority: P1)

A participant opens the Dashboard or the Topics overview to see what Topics exist. Each Topic
occupies a single, compact row. The Topic's name is the obvious way into its detail page — it
reads and behaves as a link — so no separate "View" button competes with it. The row's remaining
columns read as distinct, aligned columns rather than one merged block of controls.

**Why this priority**: This is the core complaint. Removing the per-row "View" button and keeping
rows to one line is what turns the list from a wall of buttons back into a scannable list, and it
delivers value on its own even if nothing else in this feature ships.

**Independent Test**: Load both screens with several Topics present and confirm that each Topic
occupies a single row of normal height, that clicking the Topic name opens that Topic's detail
page, and that no "View" button is rendered.

**Acceptance Scenarios**:

1. **Given** the Topics overview lists several Topics, **When** a participant views the page,
   **Then** no row renders a separate "View" / "View Details" control, and each Topic's name is a
   link to that Topic's detail page.
2. **Given** a participant clicks a Topic's name on the Dashboard, **When** the page loads,
   **Then** they arrive at that Topic's detail page — the same destination the old "View" button
   led to.
3. **Given** a Topic row carries a reference number badge and/or a "Pending approval" badge,
   **When** the row renders, **Then** the badges stay on the same line as the name and the name
   remains the clickable element.
4. **Given** a participant navigates the Topic list using only a keyboard, **When** they tab
   through a row, **Then** the Topic name receives a visible focus indicator and activating it
   with Enter opens the detail page.

---

### User Story 2 - Upvote without the control dominating the row (Priority: P1)

A participant sees how many upvotes a Topic has and can add or withdraw their own, using one
compact vote control that shows the count and their own vote state together, in a single narrow
column. The control toggles: pressing it when they have not voted records a vote, pressing it
again withdraws it.

**Why this priority**: The upvote control is the single largest contributor to the stacked-button
problem, because the count and the button currently share one cell and wrap onto separate lines.
Upvoting is also a primary participant action, so it must stay obvious and reachable.

**Independent Test**: With upvoting enabled, confirm the count and the vote control occupy one
line in one column, that pressing it increments the count and visibly marks the row as voted
without reloading the page, and that pressing it again decrements and unmarks. Repeat with
scripting disabled to confirm the form fallback still records the vote.

**Acceptance Scenarios**:

1. **Given** upvoting is enabled and a participant has not upvoted a Topic, **When** they view the
   row, **Then** they see a single control showing that Topic's current upvote count in an
   unvoted state.
2. **Given** that control, **When** the participant activates it, **Then** the count shown
   increases by one and the control switches to its voted state immediately, without waiting for
   the server, without the page reloading, and without the participant losing their place in the
   list; the upvote is recorded and the displayed value is reconciled with the server's answer.
3. **Given** a Topic the participant has already upvoted, **When** they activate the control
   again, **Then** their upvote is withdrawn, the count decreases by one, and the control returns
   to its unvoted state.
4. **Given** a participant whose browser has not run the page's scripting, **When** they activate
   the control, **Then** their vote is still recorded and they are returned to the screen they
   acted from with the updated count — the behaviour that ships today.
5. **Given** the in-place update fails (the request errors or times out), **When** that happens,
   **Then** the control and count return to the server's actual state and a brief notice appears
   next to that control saying the vote did not go through — without the row changing height or
   the table reflowing, and without the participant having to look elsewhere on the page.
6. **Given** a participant using a screen reader, **When** they reach the control, **Then** it is
   announced with the Topic name, its current voted or unvoted state, and the action it will
   perform — not merely as a number.
7. **Given** that participant, **When** they activate the control, **Then** the change of state is
   announced, and the new count is announced along with the Topic it belongs to — without
   interrupting what was already being read and without focus moving.
8. **Given** the voted and unvoted states, **When** compared, **Then** they differ by more than
   colour alone, so the state is perceivable without colour vision.
9. **Given** upvoting is disabled for the event, **When** either screen renders, **Then** no vote
   column or control appears, exactly as today.

---

### User Story 2a - Joining without losing your place (Priority: P2)

A participant browsing the full Topics overview finds a Topic they want and joins it from that
row. The page re-renders — joining changes enough about the screen that it should — but they are
returned to the Topics overview they were reading, not dropped onto a different page. The Join
control itself sits quietly beside the vote control rather than dominating the row.

**Why this priority**: it removes a jarring inconsistency this feature would otherwise create —
voting would preserve the participant's place perfectly while joining teleported them elsewhere.
Independent of the vote work, and small.

**Independent Test**: join a Topic from `/topics/overview` and confirm the resulting page is
`/topics/overview` with the confirmation message shown; repeat from the Dashboard and confirm it
returns to the Dashboard.

**Acceptance Scenarios**:

1. **Given** a participant viewing the Topics overview, **When** they join a Topic from a row,
   **Then** the Topics overview is re-rendered with the confirmation message, and they are not
   redirected to the Dashboard.
2. **Given** a participant viewing the Dashboard, **When** they join a Topic from a row, **Then**
   the Dashboard is re-rendered with the confirmation message.
3. **Given** a tampered return target submitted with the join, **When** the server handles it,
   **Then** the value is rejected and a safe default is used instead.
4. **Given** a join that cannot be completed (the Topic filled up, or the participant is already in
   a Group), **When** it is attempted, **Then** the participant is returned to the screen they
   acted from and told why it did not happen.
5. **Given** a row offering both Join and a vote control, **When** the row renders at 1024px,
   **Then** both sit on one line and Join does not visually dominate the compact vote control.

---

### User Story 3 - A Dashboard Topics card with room to breathe (Priority: P2)

A participant on the Dashboard sees the Topics card without the "Your Skills" column. The space it
freed goes to the Topic name, which no longer wraps mid-word in the card's half-width column.

**Why this priority**: It directly addresses "remove skills from the homepage, so it isn't as
small", and it is independent of the row-action work — either change improves the card on its own.

**Independent Test**: Load the Dashboard as a registered participant whose skills match at least
one listed Topic, and confirm no skills column is rendered in either Dashboard Topic table while
the Topics overview page still shows its "Needed Skills" column.

**Acceptance Scenarios**:

1. **Given** the Dashboard's "Open Topics" table, **When** it renders, **Then** it has no "Your
   Skills" column and no skills are listed per row.
2. **Given** the Dashboard's "Your Topics" table (shown when the participant authored or joined a
   Topic), **When** it renders, **Then** it likewise has no "Your Skills" column.
3. **Given** the Topics overview page, **When** it renders, **Then** its "Needed Skills" column is
   still present and populated — this feature removes skills from the Dashboard only.
4. **Given** a Topic with a long name on the Dashboard, **When** the card renders at its normal
   half-page width, **Then** the name uses the width freed by the removed column rather than
   wrapping into several short lines.
5. **Given** the Dashboard's "Open Topics" table is empty, **When** it renders, **Then** the "No
   open Topics right now." message still spans the full width of the table.

---

### User Story 4 - Quieter card-level actions (Priority: P3)

The "Propose Topic" and "All Topics" controls at the top of the Dashboard's Topics card remain
available as buttons, but are sized and weighted so they read as card controls rather than as the
loudest elements on the page.

**Why this priority**: Cosmetic refinement of controls that already work. Valuable for the overall
"cleaner UI" goal, but the list itself is the primary complaint.

**Independent Test**: Compare the Topics card before and after; both controls are still present,
still lead to the Topic proposal form and the Topics overview respectively, and occupy visibly
less vertical and horizontal weight.

**Acceptance Scenarios**:

1. **Given** the Dashboard Topics card, **When** it renders, **Then** both "Propose Topic" and
   "All Topics" are present as buttons and lead to the Topic proposal form and the Topics overview
   page respectively.
2. **Given** the two controls, **When** they render, **Then** "Propose Topic" remains the visually
   primary of the two and "All Topics" the secondary, so the intended next action stays clear.
3. **Given** the restyled controls, **When** measured against the surrounding card text, **Then**
   they are smaller and less visually dominant than the current full-size pair, while still
   meeting the minimum target size and contrast expected of an interactive control.

---

### Edge Cases

- **A Topic the participant can join**: the "Join" action changes state and cannot be inferred
  from a row, so it keeps an explicit labelled control. It sits in the row's action column
  alongside the vote control and must not reintroduce stacking.
- **Joining fails because the Topic filled up or the participant is already in a Group**: the
  participant returns to the screen they acted from — not to a different page — and is told why.
- **A row with both Join and a vote control**: the two must fit side by side on one line at the
  Dashboard card's narrow width, or the table scrolls horizontally rather than the row growing
  taller.
- **Very long Topic names**: the name link may wrap across lines, but its badges stay attached to
  it and the row's other columns stay aligned.
- **Narrow viewports**: between 768px and 1024px the Dashboard card is at its tightest — still
  two columns, but on a narrow page. At or below 768px the Dashboard stacks into one column, so the
  card regains full width. At every width the tables stay usable by letting Topic names wrap, never
  by hiding columns behind a sideways scroll and never by stacking a row's controls.
- **A very long Topic name**: it wraps onto as many lines as it needs, and its reference-number or
  "Pending approval" badge wraps with it rather than being stranded. The row grows taller; no column
  is pushed out of view.
- **A pinned Topic that is Pending approval or full**: it must still reach its detail page via the
  name link, while offering no Join control — the same rule as today.
- **Upvoting disabled, joining not permitted**: a row then has no interactive control other than
  the name link; the table must not render an empty, visually ambiguous action column.
- **Zero upvotes**: the control shows a count of zero rather than being hidden, so a participant
  can still cast the first vote.
- **Repeated or rapid activation of the vote control**: in-place updating makes fast successive
  clicks likelier than a reload-per-click ever did. At most one upvote per participant per Topic
  may be recorded, and once activity settles the displayed count must match the recorded state.
- **The in-place request fails**: the control must not keep showing an optimistic count that the
  server never accepted; it reverts and says so — visibly and to assistive technology alike, so a
  screen reader user is not told the vote succeeded when it did not.
- **The brief window between clicking and the server answering**: the count on screen is a
  prediction, not yet a fact. This is accepted deliberately in exchange for an instant response,
  and is bounded — once the answer arrives the display either stands confirmed or is reverted.
- **A participant clicks again while a request is still in flight**: the control stays active by
  design, so the display must settle on the outcome of the last activation, not on whichever
  response happens to arrive last.
- **Several rows fail at once** (the connection dropped): the notices must not pile up into a
  stack that covers the list or obscures the controls a participant would use to retry.
- **A failure notice is showing when the participant retries and succeeds**: the stale notice must
  clear itself rather than contradict the now-correct count beside it.
- **Scripting unavailable or blocked**: the control degrades to the plain form submission that
  ships today — the vote still works, it just reloads the page.

## Requirements *(mandatory)*

### Functional Requirements

**Row navigation**

- **FR-001**: Both the Dashboard Topic tables and the Topics overview tables MUST present each
  Topic's name as the link to that Topic's detail page.
- **FR-002**: The system MUST NOT render a separate per-row "View" / "View Details" control on
  either screen.
- **FR-003**: The name link MUST be reachable and activatable by keyboard, with a visible focus
  indicator.
- **FR-004**: Reference-number and "Pending approval" badges MUST continue to render beside the
  name on the same line, and MUST NOT themselves become part of the link target.

**Upvoting**

- **FR-005**: Where upvoting is enabled, each row MUST present the Topic's upvote count and the
  participant's own vote action as one compact vote control within a single column. This control is
  the canonical term used throughout this spec for the combined count-and-action element.
- **FR-006**: That control MUST toggle: activating it records an upvote when the participant has
  none, and withdraws their upvote when they have one.
- **FR-006a**: Activating the control MUST update the displayed count and the control's own state
  in place, without reloading the page and without moving the participant's scroll position or
  keyboard focus. That update MUST happen immediately on activation, before the server has
  confirmed it, so the control responds instantly; the displayed value MUST then be reconciled
  against the server's answer when it arrives (FR-006c governs the failure path).
- **FR-006b**: The control MUST remain a working form submission when client-side scripting is
  unavailable or fails to load. In that case activation falls back to today's behaviour: the
  action is still recorded and the participant is returned to the screen they acted from. No
  participant may be left unable to vote because scripting did not run.
- **FR-006c**: Where an in-place update cannot be completed — the request fails, times out, or is
  rejected — the control and count MUST be reverted to the state the server actually holds, and
  the failure MUST be made visible to the participant rather than silently discarded. Beyond the
  brief window between activation and the server's answer, a count that disagrees with the recorded
  state MUST NOT remain on screen.
- **FR-006c1**: The failure notice MUST appear next to the control that failed, not only at the
  page level, so a participant acting on a row far down a long list sees the explanation where they
  are already looking.
- **FR-006c2**: That notice MUST overlay the surrounding layout rather than be inserted into it:
  showing or dismissing it MUST NOT change the row's height, reflow the table, or push any other
  row — the single-line guarantee of FR-013 holds while a failure notice is on screen.
- **FR-006c3**: The notice MUST be dismissible and MUST NOT trap keyboard focus or obscure the
  control it describes, so a participant can read it and immediately retry the vote.
- **FR-006d**: Repeated or rapid activation MUST converge on the participant's final intent: at
  most one upvote per participant per Topic is ever recorded, and once activity settles the
  displayed count and voted state MUST match what the server holds. Because the control stays
  active during a request (FR-006a), overlapping activations MUST NOT leave the display stuck on
  the result of an earlier, superseded one.
- **FR-007**: The control MUST visually distinguish the voted state from the unvoted state by at
  least one cue other than colour.
- **FR-008**: The control MUST carry an accessible name that identifies both the Topic and the
  action it performs.
- **FR-008a**: The control MUST expose its voted/unvoted state as a toggle state that assistive
  technology can read and that changes when the vote is cast or withdrawn, so activating it
  announces the result without the participant having to go looking for confirmation.
- **FR-008b**: After an in-place update the new count MUST be announced politely — that is,
  conveyed to assistive technology without interrupting whatever it is currently reading and
  without moving focus. The announcement MUST identify the Topic, so a participant who voted on one
  row is not left guessing which count changed.
- **FR-008c**: The toggle state (FR-008a) and the announcement (FR-008b) MUST agree with the
  visible control at all times, including after a failed update has reverted it (FR-006c).
- **FR-008d**: Because the display updates optimistically (FR-006a), an announcement MUST NOT
  tell a participant their vote succeeded before the server has confirmed it. Where an optimistic
  change is subsequently reverted, that reversal MUST itself be announced, so a participant relying
  on announcements is never left believing a vote landed when it did not.
- **FR-008e**: The failure notice (FR-006c1) MUST reach a participant who cannot see it — its
  message MUST be conveyed to assistive technology, not carried by the visual notice alone.
- **FR-009**: Where upvoting is disabled, the system MUST NOT render a vote column or control on
  either screen.
- **FR-010**: After an upvote or withdrawal the participant MUST remain on the screen they acted
  from with the updated count shown — in place when the enhancement is active (FR-006a), via the
  existing return-to-screen redirect when it is not (FR-006b).

**Joining**

- **FR-011**: Where a participant may join a Topic, the row MUST continue to offer an explicitly
  labelled Join control.
- **FR-011a**: Joining MUST return the participant to the screen they acted from. A join initiated
  from the Topics overview MUST return to the Topics overview, and one initiated from the Dashboard
  MUST return to the Dashboard. Today both return to the Dashboard regardless, which this feature
  corrects so that joining and voting behave consistently about not losing the participant's place.
- **FR-011b**: The return target MUST be validated server-side against an allow-list, so a
  tampered value cannot turn joining into an open redirect — the same protection the upvote
  actions already apply.
- **FR-011c**: The Join control MUST be sized and weighted to sit comfortably beside the compact
  vote control, rather than remaining at the full button size that contributed to the stacking this
  feature removes. It MUST keep its visible text label (FR-011) and remain comfortably usable by
  touch.
- **FR-011d**: Joining MUST remain a full-page action that re-renders the screen. It MUST NOT be
  converted to an in-place update: joining changes state beyond the row acted on — the participant's
  status, which Topics are pinned as theirs, member counts, and whether any other row is joinable at
  all — so only a re-render can leave the whole screen truthful.
- **FR-012**: At 1024px and wider, the Join control and the vote control MUST sit on one line
  within the row, including inside the Dashboard card at its half-page width. At no width may a
  row-level control wrap onto a line of its own.
- **FR-012a**: The Topics overview MUST NOT scroll horizontally. Where its content does not fit the
  viewport, the Topic name MUST wrap onto further lines so that every column — including the
  right-hand ones — stays visible without sideways scrolling. A column a reader cannot see is worse
  than a title that takes two lines.
- **FR-012b**: FR-012a governs text only. Wrapping a Topic name MUST NOT be taken as licence to
  wrap a row's controls; those remain on one line (FR-013), which is the defect this feature exists
  to remove.

**Row density**

- **FR-013**: At a viewport width of 1024px or wider, a Topic row carrying a vote control and,
  where applicable, a Join control MUST occupy a single line of controls — no row-level control may
  wrap onto a line of its own. Below 1024px a row may grow taller because its Topic name wrapped
  (FR-012a); its controls still MUST NOT stack.
- **FR-014**: Column boundaries MUST remain visually distinguishable, so the vote count is not read
  as belonging to the action column.

**Dashboard skills**

- **FR-015**: The system MUST NOT render a skills column or per-row skill values in either
  Dashboard Topic table ("Your Topics", "Open Topics").
- **FR-015a**: Serving the Dashboard MUST NOT compute which of the viewer's skills each Topic
  needs. The matching-skills field MUST be removed from the Dashboard's Topic row data together
  with the work that populates it, so no effort is spent on a column that no longer exists.
- **FR-015b**: Before that removal, it MUST be confirmed that no other screen or behaviour reads
  the Dashboard row's matching-skills field. Removal MUST NOT change what the Topics overview or
  the Topic detail page shows.
- **FR-016**: The Topics overview page MUST retain its "Needed Skills" column unchanged.
- **FR-017**: Empty-state rows and any column-spanning messages MUST span the table's actual
  column count after the skills column is removed.

**Card actions**

- **FR-018**: The Dashboard Topics card MUST continue to offer both "Propose Topic" and "All
  Topics", leading to the Topic proposal form and the Topics overview page respectively.
- **FR-019**: Of the two, "Propose Topic" MUST read as the primary action and "All Topics" as the
  secondary.
- **FR-020**: Both controls MUST be rendered at reduced visual weight relative to their current
  full-size presentation, while remaining large enough and high enough in contrast to be used
  comfortably, including by touch.

**Scope boundary**

- **FR-021**: Changes MUST be confined to the participant-facing Dashboard and Topics overview
  screens, together with the data each of those two screens is served with. Organiser-space screens
  MUST render unchanged.
- **FR-021a**: The Topic detail page MUST render unchanged, including its existing labelled
  "Upvote" / "Withdraw upvote" button. The compact vote control introduced by FR-005 applies to the
  two list screens only; the same action is therefore deliberately presented differently in a list
  than on a single-Topic page.
- **FR-022**: No capability available to a participant before this change may be unavailable
  after it: navigating to a Topic, upvoting, withdrawing an upvote, joining, proposing a Topic and
  reaching the full Topics list all remain possible.
- **FR-023**: Which Topics appear, in which sections, and in what order MUST be unchanged by this
  feature.

### Key Entities

No new entities. The feature re-presents existing ones:

- **Topic**: name, reference number, approval status, participant count, needed skills, upvote
  count.
- **Topic row (as shown to a viewer)**: the viewer-specific view of a Topic — whether it is
  pinned, whether the viewer may join it, and whether the viewer has upvoted it. The Dashboard's
  variant previously also carried which of the viewer's skills the Topic needs; that field and the
  work behind it are removed (FR-015a). The Topics overview's own row keeps the Topic's needed
  skills, which is a different, viewer-independent value.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On both screens at a viewport width of 1024px, every Topic row occupies one line of
  controls — zero rows render a control stacked beneath another control. At narrower widths rows
  still never stack controls; a long Topic name wraps instead.
- **SC-001a**: The Topics overview never requires horizontal scrolling to reach a column: every
  column is visible at any viewport width of 360px or more.
- **SC-002**: The number of visible interactive controls per Topic row drops from three (upvote,
  join where applicable, view) to at most two, and to exactly one for a row the viewer cannot
  join.
- **SC-003**: Vertical space per Topic row is reduced by at least 50% compared with the current
  stacked presentation, so a participant sees at least twice as many Topics without scrolling.
- **SC-004**: On the Dashboard, the width available to the Topic name column increases measurably
  once the skills column is removed, and no Topic name of 40 characters or fewer wraps in the card
  at its normal half-page width.
- **SC-005**: A participant can go from opening either screen to a chosen Topic's detail page in a
  single click or key activation.
- **SC-005a**: Joining a Topic from either screen returns the participant to that same screen —
  measured as: the page shown after joining is the one they acted from, in 100% of cases and from
  both screens.
- **SC-006**: Every interactive element on both screens is reachable by keyboard and carries an
  accessible name identifying its Topic and action; an automated WCAG 2.1 AA scan of both screens
  reports zero critical or serious violations — the bar the project's existing automated
  accessibility checks for these two screens already enforce.
- **SC-006a**: A participant using only a screen reader can cast a vote and confirm it landed
  without sighted help: the state change and the new count are both announced, and the announced
  count matches the recorded one.
- **SC-007**: Every state distinction introduced or changed by this feature — notably voted versus
  unvoted — is perceivable without colour vision.
- **SC-008**: Every organiser-space screen and the Topic detail page are unchanged from before the
  feature, confirming the scope boundary held.
- **SC-009**: Casting or withdrawing an upvote does not reload the page, and the participant's
  scroll position and keyboard focus are exactly where they were before they activated the
  control — so voting on the last Topic in a long list never returns them to the first.
- **SC-009a**: When a vote fails, the participant can tell what happened without scrolling or
  hunting: the explanation is adjacent to the control they used, and no row has changed size.
- **SC-010**: With client-side scripting disabled, every action on both screens still works:
  navigating to a Topic, upvoting, withdrawing an upvote, and joining all succeed.
- **SC-011**: The Topics overview and the Topic detail page show exactly the skills they showed
  before, and the Dashboard loads no slower than it did previously — removing the skills column
  costs nothing elsewhere and returns the effort it used to spend.

## Assumptions

- **"Buttons look very unwanted"** is read as a complaint about visual weight and the stacked
  layout, not about the underlying actions. Every action is retained; only its presentation
  changes. This was confirmed with the requester.
- **"Remove skills from the homepage"** means the "Your Skills" column in the Dashboard's Topic
  tables, not the skills feature as a whole. The Topics overview keeps "Needed Skills". Confirmed
  with the requester.
- **"So it isn't as small"** refers to the cramped Topic name column on the Dashboard card; the
  freed width goes to the name. The card itself keeps its existing share of the page.
- **Visible columns beat unwrapped titles.** The first implementation kept Topic names on one line
  and let the table scroll sideways. Seen running, that hid the Compliance, Upvotes and Join columns
  off the right-hand edge where a reader would not know to look for them. The priority was corrected
  after that feedback: names wrap, and the table is sized by the viewport rather than by its longest
  title.
- **The per-row "View" button is redundant** once the Topic name links to the same destination.
  Confirmed with the requester, who chose the "name is the link, compact vote control" presentation
  over keeping explicit buttons.
- **"Propose Topic" and "All Topics" both stay as buttons**, restyled smaller and quieter rather
  than one being demoted to a plain link. Confirmed with the requester.
- **Scope is the two participant list screens only.** Organiser-space tables share some of the
  same underlying presentation concerns but are explicitly out of scope. The Topic detail page is
  also out of scope and keeps its labelled upvote button — a list and a single-Topic page have
  different density pressures, so the same action is allowed to look different in each. Both
  confirmed with the requester.
- **Join keeps an explicit text label.** It changes state in a way a participant cannot undo
  without a further action, so it is not reduced to an icon the way the vote control is. It is
  quieted to match the compact vote control's weight, but not shrunk past comfortable touch use
  (FR-011c).
- **Join stays synchronous, deliberately.** It was considered for the same in-place treatment as
  voting and rejected: joining changes the participant's status, the pinned "Your Topics" section,
  member counts, and whether any other row is joinable at all, so updating one cell in place would
  leave the rest of the screen stale. The return-to-screen fix (FR-011a) gives it the part of the
  benefit that actually applies.
- **Joining currently ignores where it was clicked.** This is pre-existing behaviour, not a
  regression introduced here: the join action always returns to the Dashboard. It is corrected in
  this feature because leaving it would make voting and joining behave visibly differently about
  preserving the participant's place. Confirmed with the requester.
- **Existing visual language is reused.** The compact vote control and the restyled card actions
  are expected to be expressible within the project's existing styling approach and token palette;
  no new visual system is introduced.
- **In-place updating is a progressive enhancement, not a rewrite.** It layers over the form that
  already exists, which stays the fallback (FR-006b). No client-side rendering framework is
  introduced — the project already ships small amounts of plain page scripting for other controls,
  and this follows that precedent.
- **The display is optimistic and self-correcting.** The requester chose an instant response over
  a guaranteed-correct one: the count changes on click and is reconciled against the server
  afterwards, reverting and reporting if the vote did not land (FR-006a, FR-006c). The trade-off
  accepted is a brief window in which the on-screen count is a prediction. Announcements to
  assistive technology are deliberately held to a stricter rule (FR-008d) — they must never claim
  success the server has not confirmed.
- **A row-anchored failure notice is a new pattern for this app.** The project's existing message
  banners are page-level and server-rendered on a redirect, which suits a page-level outcome but
  not a failure attached to one row of a long list. Introducing an overlaying, row-anchored notice
  is a deliberate addition, constrained by FR-006c2 so it cannot disturb the row density this
  feature exists to fix.
- **Sorting, filtering and pagination are out of scope.** This feature changes how rows look, not
  which rows appear or in what order.
- **The reported screenshots show a build whose detail control was labelled "View Details"; the
  current label is "View".** This is cosmetic — the structural problem (stacked controls in a
  cramped column) is present in both, and the control is removed either way.

# Contract: Rendered Topic Row

**Feature**: [../spec.md](../spec.md) | **Screens**: `/` (Dashboard card), `/topics/overview`

The UI contract both screens must satisfy. Written as observable properties of the rendered page,
so it can be asserted without knowing how the templates are organised.

## Column shape

### Dashboard — "Your Topics" and "Open Topics"

| Column | Present when | Content |
|---|---|---|
| Name | always | Topic name **as a link** to `/topics/{id}`, plus reference-number and (pinned table only) "Pending approval" badges, on one line |
| Participants | always | member count |
| ~~Your Skills~~ | **never** | removed by FR-015 |
| Votes | `upvotingEnabled` | the compact vote control (count + toggle) |
| *(join)* | `canJoinTopics` | labelled "Join" button on joinable rows; empty cell otherwise |
| ~~*(view)*~~ | **never** | removed by FR-002 |

### Topics overview — "Your Topics" and "All Topics"

Same as above, except: `Author` and `Participants` and `Needed Skills` are retained
(`Needed Skills` by FR-016), and there is no "Your Skills" column to begin with.

Empty-state rows must span the **actual** post-removal column count (FR-017). On the Dashboard this
drops by one.

## Required properties

| # | Property | Source |
|---|---|---|
| R1 | The Topic name is a link whose href is `/topics/{id}` | FR-001 |
| R2 | No element with the text "View" or "View Details" exists in any row | FR-002 |
| R3 | The name link is keyboard-focusable with a visible focus indicator | FR-003 |
| R4 | Badges render beside the name, on the same line, outside the link target | FR-004 |
| R5 | At ≥1024px, no row renders any control on a line below another control | FR-013 |
| R6 | The Topics overview never scrolls horizontally; a long Topic name wraps instead, so every column stays visible | FR-012a |
| R6a | Wrapping applies to text only — a row's controls never wrap onto their own line | FR-012b, FR-013 |
| R6b | Every cell fills the full row height; row separators span the whole table | FR-014a |
| R7 | Voted and unvoted differ by more than colour | FR-007 |
| R8 | Every control's accessible name identifies its Topic and its action | FR-008 |
| R9 | The vote control exposes `aria-pressed` reflecting the viewer's state | FR-008a |
| R10 | A single visually-hidden `role="status" aria-live="polite"` region exists per screen | FR-008b |
| R11 | Showing or hiding a failure notice changes no row's height | FR-006c2 |
| R12 | A failure notice does not obscure the control it describes and is dismissible | FR-006c3 |
| R13 | With `upvotingEnabled` false, no vote column, control or live region renders | FR-009 |
| R14 | Organiser-space pages and `/topics/{id}` render unchanged | FR-021, FR-021a |
| R15 | The Join form carries a `redirect` field naming the screen it is rendered on | FR-011a |
| R16 | Joining returns to that screen; a value outside the allow-list falls back to `/` | FR-011a, FR-011b |
| R17 | Join is sized to sit beside the compact vote control without dominating it, and keeps its text label | FR-011c |
| R18 | Joining re-renders the page; it is **not** an in-place update | FR-011d |

## CSS contract

| Class | Scope | Must not |
|---|---|---|
| `.actions` | shared, app-wide | **be modified**, and **never be placed on a `<td>`** — `display:flex` takes the cell out of the table formatting context so it stops filling the row (FR-014a). Put it on an element inside the cell |
| `.actions-nowrap` | new; these two screens only | apply to any organiser template |
| `.table-scroll` | existing; Dashboard card only | be added to the overview — it must not scroll sideways (FR-012a) |
| `.name-with-badge` | these two screens only | re-introduce `white-space: nowrap`, which is what forced the overview to scroll |
| `.vote-control`, `.vote-notice` | new; these two screens only | introduce a colour outside the existing `--app-*` / Pico token palette (Constitution IV) |

See [../research.md](../research.md) §7 for why the modifier class exists rather than an edit to
`.actions` — this is the single most likely accidental scope violation in the feature.

## Card actions (Dashboard Topics card)

| # | Property | Source |
|---|---|---|
| C1 | Both "Propose Topic" and "All Topics" render, as buttons | FR-018 |
| C2 | "Propose Topic" reads as primary, "All Topics" as secondary | FR-019 |
| C3 | Both are visually lighter than the current full-size pair, while remaining comfortably usable by touch | FR-020 |

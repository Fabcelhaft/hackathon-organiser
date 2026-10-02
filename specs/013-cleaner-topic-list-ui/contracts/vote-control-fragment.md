# Contract: Vote Control Fragment

**Feature**: [../spec.md](../spec.md) | **Routes**: `POST /topics/{id}/upvote`, `POST /topics/{id}/unupvote`

This contract covers the *new* branch only. The existing redirect behaviour is unchanged and is
restated at the bottom because the fallback depends on it staying exactly as it is.

## Request

| | |
|---|---|
| Method | `POST` |
| Path | `/topics/{id}/upvote` or `/topics/{id}/unupvote` |
| Header | `X-Vote-Fragment: true` — **this header alone selects the fragment branch** |
| Body | empty (the `redirect` field is not read on this branch) |
| Auth | authenticated session, as today |
| CSRF | none required — disabled project-wide (`SecurityConfig`) |

## Response — success

| | |
|---|---|
| Status | `200 OK` |
| Content-Type | `text/html;charset=UTF-8` |
| Body | the rendered `fragments/topic-vote :: control(...)` fragment for that one Topic |

The body is the authoritative post-action state. It MUST contain exactly these four values, which
are the only things the client reads:

| Value | Where it lives in the fragment | Consumed as |
|---|---|---|
| Upvote count | text of `[data-vote-count]` | the number shown beside the control |
| Voted state | `aria-pressed` on `button[data-vote-button]` | the control's pressed state |
| Accessible name | `aria-label` on `button[data-vote-button]` | the control's announced name |
| Next action | `action` on `form[data-vote-form]` | where the next activation posts |

Those four attribute/selector names are the contract. Renaming any of them is a breaking change to
`topic-vote.js`.

### Accessible-name wording (load-bearing)

| State | `aria-label` |
|---|---|
| not voted | `Upvote {topic name}` |
| voted | `Withdraw upvote for {topic name}` |

This wording is **not** cosmetic. The visible label becomes an icon plus a count, so these strings
become the only occurrence of the words "Upvote" and "Withdraw upvote" in the rendered page, and
existing assertions in `HomeControllerIT` and `TopicOverviewManagementIT` match on exactly them.
See [../research.md](../research.md) §3.

## Response — failure

| Condition | Status | Client behaviour |
|---|---|---|
| Upvoting disabled | `404` | revert, show the row-anchored notice |
| Topic not visible / unknown | `404` | revert, show the row-anchored notice |
| Unauthenticated (session expired) | `302` to login | revert, show the notice; do not follow |
| Server error | `5xx` | revert, show the notice |
| Network failure / timeout | — | revert, show the notice |

In every failure case the client MUST restore the pre-activation count and pressed state
(FR-006c), and MUST NOT announce success (FR-008d).

## Response — fallback branch (unchanged, restated)

When `X-Vote-Fragment` is absent:

| | |
|---|---|
| Status | `303 See Other` |
| Location | the `redirect` form value if it passes the allow-list (`/`, `/topics/overview`, `/topics/{uuid}`), else `/` |

This is what a no-scripting form submission gets, and it MUST keep working byte-for-byte — it is
the whole of FR-006b. Any change here breaks the fallback silently for the users least able to
report it.

## Idempotency

`POST /upvote` on an already-upvoted Topic succeeds and changes nothing; `POST /unupvote` with no
existing upvote likewise. Guaranteed by `topic_upvotes`' composite primary key and
`TopicUpvoteService`'s pre-check — not by the client. Overlapping activations are therefore safe
at the data layer; the client's only job is to settle the *display* on the last activation
(FR-006d).

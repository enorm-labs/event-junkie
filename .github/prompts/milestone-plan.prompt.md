# Milestone Plan

Take a whole milestone from "a list of open issues" to "a plan you can work": correct the issues that have gone stale, decide which ones belong in it at all,
and put the survivors in an order. The milestone-scale counterpart to [`/next-issue`](next-issue.prompt.md), which answers the same question for one issue.

Usage: `/milestone-plan v0.3 — Launch-ready`

## Important

- **Correct the backlog before ordering it.** An issue whose premise is false cannot be sequenced, and a `blocked` label that is no longer true removes work
  from the plan silently. Do §1 first or the order you produce is an order over fiction.
- **Verify against the tree and the API, never against the issue text.** An issue body is a claim made on the day it was filed. This whole skill exists because
  those claims rot.
- **Propose, then apply.** Editing issues and a public project board is visible to everyone watching the repository. Produce the plan, show what you would
  change, and get agreement — unless the user has already said to apply it.
- **The plan is a Markdown file in `temp/`, formatted.** See AGENTS.md § Agent Instructions. `scripts/format-markdown.sh temp/<file>.md` — naming it explicitly,
  because the default scope does not reach a gitignored directory.

## Steps

### 1 · Read the milestone

```sh
gh issue list --milestone '<title>' --state open --limit 60 \
  --json number,title,labels,createdAt,updatedAt,assignees
gh project item-list 1 --owner enorm-labs --format json --limit 600   # Status and Priority
```

Then read **every open issue in full**, including its comments and its Links footer. This is the expensive step and the one that earns the skill its place; a
plan built from titles is a guess with a table in it.

### 2 · Find what has gone stale

For each issue, check its claims rather than believing them. The five that actually recur:

1. **A closed blocker.** Take every issue named in `Blocked by` and get its state. Closed blockers are the most common defect and the most valuable to find,
   because the issue has been invisible for however long it has been unblocked.
2. **A blocker that closed and handed off to another issue.** Read the comments — a correction often lives there while the body still names the original.
   **Then check the successor too.** A blocker that was split and re-scoped may no longer block anything.
3. **Numbers, paths and tool names in the body.** Version pins move, files get renamed, tools get replaced. Grep for each one. A table of pins written three
   weeks ago is the single least reliable thing in a backlog.
4. **Work that has already partly landed.** `git log -S '<symbol>' -- <path>` on whatever the issue says is missing.
5. **A done-when item that a later decision made moot.** Something removed from the architecture takes its issues' checkboxes with it, and nobody goes back.

**The check that settles a doubtful blocker: `git log -S` on the thing the blocker supposedly gates.** If work of exactly that kind has been landing while the
blocker sat open, it does not block. This is worth more than any amount of reading, because it is evidence about behaviour rather than about intent.

**A worked example, because this one nearly reached a published document.** #796 was blocked by #877, and #877's body said nine alert rules existed.
`deploy/alerts/alerts.json` held eleven — the two extras landed the day _after_ #877 was filed, while it was open and blocked. New rules were already shipping
without it, so it blocked nothing. The same issue's body also said production ran no OpenObserve; #880 had given it one a day later, and taking that sentence at
face value produced a privacy-notice claim that no log retention period existed when the configured answer was 14 days. **Both errors came from the same
habit — reading an issue for a fact about the system.** An issue is evidence about the day it was written.

### 3 · Read the board against the labels

Status and Priority are project fields, labels are intrinsic (AGENTS.md § The Backlog). They disagree in specific, recognisable ways:

| Symptom                             | What it usually means                                                                                                                                |
| ----------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Blocked** _and_ **P0**            | A contradiction the board cannot express. Almost always a stale blocker — P0 says do it now, Blocked says you cannot                                 |
| **Blocked** with no `blocked` label | Frequently a `needs-decision` issue filed under the wrong status. **A decision issue is a blocker, not blocked** — making the decision _is_ the work |
| `blocked` label, blocker closed     | §2's finding, seen from the other side                                                                                                               |
| Not on the board at all             | Invisible to every board view. Newly filed issues are the usual cause                                                                                |

### 4 · Ask whether each issue belongs in this milestone

Two questions, and the second is the one people skip:

- **Does anything this gates fall inside the milestone?** An issue whose entire downstream sits in a later milestone is not holding this one up. Moving it out
  costs nothing and makes the milestone mean something.
- **Is anything in this milestone blocked by an issue in a _later_ one?** That inversion means the milestone **cannot close**, and it is invisible from any
  single issue. Either the blocker moves forward or the blocked issue moves back.

**Moving work _into_ the milestone is a legitimate outcome.** A broken deployment gate sitting in a post-launch bucket belongs before launch. A skill that only
ever removes issues is a skill for making a number look better, and the number is not the point.

Say what changes and why, and leave the decision to the user. When a move is applied, **record it as a comment on the issue** — a milestone change leaves no
trace in the timeline, so an unexplained one reads as churn six weeks later.

### 5 · Order what is left

Sequence on three things, in this order:

1. **What unblocks the most.** Follow the chains; the head of the longest one goes first.
2. **What has external lead time.** A legal review, a hardware purchase, a registration that needs a human elsewhere. Start procurement early and in parallel —
   the work waits on somebody else's calendar, not on effort.
3. **What gets cheaper done before something else.** A breaking API change is nearly free with no consumers and expensive once a UI reads it. Two coupled
   decisions cost less made together than separately.

Group into waves, and say what each issue is waiting for rather than only where it sits. Call out the `size:XL` and `size:L` items explicitly: they decide when
the milestone actually closes, and `size:XL` is a defect flag rather than an estimate (see [`/start-issue`](start-issue.prompt.md)).

### 6 · Write the plan

`temp/<milestone-slug>-plan.md`, then `scripts/format-markdown.sh temp/<milestone-slug>-plan.md`. Cover:

- **The corrections**, one entry each, with **the command that proves it** beside the finding. The next reader should be able to re-check any row in one line.
- **Issues verified as still accurate**, listed briefly. Recording what you checked and found sound stops the next run re-checking it.
- **The order**, as waves, with the reason each issue sits where it does.
- **The milestone-fit questions**, answered or flagged.
- **What the milestone now contains**, as a count and an explicit list of numbers.

Write §1 in the past tense once it is applied, so the file reads as a record of decisions rather than a to-do list that has quietly gone stale — which is the
failure this whole skill is about.

### 7 · Apply, once agreed

```sh
scripts/issue-board.sh batch temp/board-fixes.txt   # one resolve, not one per issue
gh issue edit <n> --remove-label blocked
gh issue edit <n> --milestone '<title>'
```

- **`batch` over repeated single calls.** The single-issue path re-resolves every id per call and sixteen back-to-back calls trip GitHub's _secondary_ rate
  limiter, which reports "API rate limit exceeded" while `gh api rate_limit` still shows thousands of points.
- **The `-` placeholder works only for the status column.** Priority is read off the end of the line by shape (`^[Pp][0-9]$`), so `278 Ready -` parses the
  status as `Ready -` and fails validation. To leave priority alone, omit it.
- **Append a dated note to the body; do not silently rewrite it.** The original premise and its correction should both stay readable — that is the repository's
  convention on these issues, and it is what lets the next reader see that the claim was once true.
- **Correct both sides of a dependency.** If A no longer blocks B, B's Links footer _and_ A's "Blocks:" line are both wrong. Fixing one leaves two issues
  disagreeing.

## Notes

- **Your own corrections can be wrong.** A correction written from a comment rather than from the tree is a second-hand claim. If a later check contradicts a
  note you added an hour ago, revise it in place and say plainly that the earlier version was wrong — a chain of three corrections on one issue is unreadable.
- **A milestone that shrinks is not a milestone that got easier.** Report what left, what arrived, and what the remaining large items are. "Sixteen instead of
  eighteen" says nothing if one of the two arrivals is a broken gate.
- **Findings that are not this milestone → `/new-issue`.** A pass like this reliably turns up defects nobody filed. File them; do not widen the plan.
- **An issue that is simply wrong should be closed**, with an explanation, rather than carried and re-read every time. Say so and propose it.

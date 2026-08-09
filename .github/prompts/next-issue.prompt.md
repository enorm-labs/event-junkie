# Next Issue

Recommend what to work on next, and explain **why** — not just what.

## Important

- This skill **reads and recommends**. It doesn't assign, branch or start work — that's [`/start-issue`](start-issue.prompt.md).
- Give **one recommendation** with two or three runners-up, not a ranked list of twenty. A list is the question restated; a recommendation is an answer.
- If the user named a constraint — "something small", "frontend only", "an hour before dinner" — that's the filter. Honour it rather than recommending the
  objectively most important thing.

## Steps

1. **Read the board's Ready column.** This is the starting set: understood, unblocked, unassigned.

   ```sh
   gh issue list --state open --limit 200 \
     --json number,title,labels,milestone,issueType,assignees
   ```

   Cross-reference `docs/BACKLOG.md` for a fast grouped view — it's a generated mirror of every open issue with type, area, size and state per row, and reading
   it costs nothing.

2. **Rule out what isn't actually available.** An issue is not pickable if it carries:
    - `blocked` — its blocker is named in the body; check whether the blocker has since closed, because nothing updates the label automatically
    - `needs-decision` — unless the *decision itself* is the work you're recommending, which is often the right answer
    - `needs-deployment` — it is blocked on a live origin, not on effort. **This is not neglected work** and should never be recommended as "easy".

3. **Weigh what's left.** In rough order:

   | Signal | Why it matters |
   |---|---|
   | **Unblocking value** | An issue that unblocks three others outranks its own priority. Check what names it in a `Blocked by:` footer. |
   | **Milestone urgency** | `v0.2 — Deployable` gates `v0.3`, which gates `v1.0`. Nothing in the launch path should wait behind Phase 2 polish. |
   | **Priority** | `P0 — now` · `P1 — next` · `P2 — later`, from the board. |
   | **Size against the user's available time** | A `size:L` recommended to someone with an hour is a bad recommendation however important it is. |
   | **Batching** | Several cross-cutting parser fixes each need a `--full` re-seed and a diff. Doing three together costs barely more than one. Say so. |

4. **Check the recommendation is really ready.** Open it (`scripts/issue-board.sh show <n>`, `gh issue view <n>`) and read the body, not just the title. Look
   for a dependency stated in prose that never became a label — that happens, and it's the most common reason a "ready" issue stalls in the first ten minutes.

5. **Recommend.** For the pick, give:
    - the number, title and milestone
    - **why this one now** — one or two sentences of actual reasoning, not a restatement of its labels
    - what it unblocks, if anything
    - anything to know before starting: a decision it depends on, whether it needs a `--full` re-seed, files it will touch
    - then two or three runners-up in a line each, and say what would make each of them the better pick instead

6. **Offer the next step** — `/start-issue <n>`.

## Notes

- **"Nothing is ready" is a real answer.** If everything unblocked is `needs-decision`, the recommendation is to *make one of those decisions*, and say which
  unblocks the most.
- **Prefer the blocker over the blocked.** When the best-looking issue is `blocked`, recommending its blocker is usually more useful than recommending the
  next-best available thing.
- **A stale `blocked` label is worth reporting.** If the named blocker has closed, say so and offer to clear the label — the board's Blocked view is only
  trustworthy if it's maintained.
- Don't recommend an issue that's already assigned, unless the user is the assignee.

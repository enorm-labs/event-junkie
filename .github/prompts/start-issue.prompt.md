# Start Issue

Pick up an issue: claim it, move it on the board, cut the branch, read everything it depends on, and produce a plan — **before** writing code.

Usage: `/start-issue 313`

## Important

- **Plan, don't implement.** This skill ends with a plan the user approves. It is the deliberate pause between "which issue" and "here is a diff".
- **Never start from another feature branch.** `git checkout -b` from one stacks the new branch on the old, so the PR carries the parent's commits and its diff
  is wider than the change. It merges cleanly and still says "into main", which is exactly why it gets missed. Always `git checkout main` first.
- Claiming an issue and moving it to _In progress_ is visible on a public board. That's fine and expected — but don't do it for an issue the user is only asking
  about.

## Steps

1. **Read the issue properly.**

    ```sh
    scripts/issue-board.sh show <n>     # type, labels, milestone, assignee, Status, Priority
    gh issue view <n>                   # the full body, including its Links footer
    ```

2. **Check it is actually startable.** Stop and say so if:
    - it's `blocked` and the blocker is still open — offer the blocker instead
    - it's `needs-decision` and the decision is unmade — the decision is the work; offer to do that
    - it's `needs-deployment` — it is not blocked on effort and cannot be finished locally
    - it's `size:XL` — that label is a _defect flag_, not an estimate. Split it into sub-issues first.
    - it's already assigned to someone else

3. **Read what it depends on.** This is the step that earns the skill its place, and the one most worth not rushing:
    - every issue named in its **Links** footer — `Blocked by`, `Related`, `Parent`
    - every file, function and document the body names. The bodies cite real paths (`EventUpsertService.dropPastEvents`, `ArtistNameMapping.kt`) precisely so
      this is a read rather than a hunt
    - any **ADR** it references — several issues exist only because an ADR decided something, and the ADR carries constraints the issue summarises
    - for an importer defect: **the scraper's KDoc**, which is the home for that source's accepted limitations. Something the KDoc records as deliberate is not
      a bug to fix.

4. **Claim it.**

    ```sh
    gh issue edit <n> --add-assignee @me
    scripts/issue-board.sh status <n> 'In progress'
    ```

5. **Cut the branch from `main`.**

    ```sh
    git checkout main && git pull
    git checkout -b <type>/<n>-<slug>     # fix/313-heimathafen-genre-taxonomy
    ```

    The type comes from the issue's own type and area: `feat/`, `fix/`, `docs/`, `chore/`, `refactor/`. Including the issue number makes the branch
    self-documenting in `git branch` and in the PR list.

6. **Write the plan.** Cover:
    - **What changes** — the files, and what happens in each
    - **How it will be verified** — which tests, and whether [`/verify`](verify.prompt.md) is needed in full or in part
    - **Whether it needs a `--full` re-seed and a diff.** Any change to shared normalization does. The issue's own form asks this; confirm the answer still
      holds rather than trusting it. It's usually the difference between a one-hour change and a one-day one.
    - **What could go wrong** — especially anything cross-cutting. A parser change that looks local to one venue often is not.
    - **What is explicitly out of scope**, so the PR stays reviewable

7. **Present the plan and stop.** Get agreement before writing code.

## After the plan is approved

- Implement, then [`/verify`](verify.prompt.md) — or the relevant subset.
- For an importer change, [`/importer-smoke`](importer-smoke.prompt.md) is the runtime check: seed, import, inspect the rows, diff against a snapshot.
- Ship with [`/open-pr`](open-pr.prompt.md), which puts **`Closes #<n>`** in the PR body.
- Move the board on as you go: `scripts/issue-board.sh status <n> 'In review'` once the PR is open. Merging the PR closes the issue and the board follows.

## Notes

- **Findings that aren't this issue → a new issue.** Work like this reliably turns up two or three neighbouring problems. File them with `/new-issue` rather
  than widening this branch; scope creep in a parser PR is how a one-file change becomes unreviewable.
- **If the issue turns out to be wrong** — already fixed, no longer true, resting on a false premise — say so and propose closing it with an explanation. Some
  of these were extracted from a backlog that had been accumulating for months, and one KDoc reference was already stale when it was migrated.
- **An accepted limitation is not a bug.** If the scraper's KDoc says the venue never publishes the field, the issue is the thing that's wrong.

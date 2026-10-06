# Maintain

One entry point for the maintenance loop: read where the loop stands, name the one step that is next, and run it when it is a step this command may
run. The steps are the existing commands; this command only joins them.

```text
/daily-check → file the drafts → /afk → /afk merge → cut a release → /post-release → again
```

## Important

- **Invoking this command is permission to run the steps marked _runs_ below**, each under its own prompt's rules and nothing more. `/daily-check` merges
  the bot pull requests its Step 5 allows, `/afk` opens draft pull requests, `/post-release` runs the due steps it finds.
- **Never, from here:** approve or merge a pull request, arm auto-merge, cut a release, dispatch `cut-release.yml`, add or remove `afk-ok`, file an issue
  the operator has not named, or write to a cluster outside `/post-release`. Each of these is a person's decision; this command prepares it and stops.
- **One step per invocation.** Run it, then re-read the state and name the next. A question for the operator ends the turn.
- **The state lives on GitHub and in `temp/`**, never in this conversation. Any session can pick the loop up, and a summary loses nothing.
- `git --no-pager`, `gh` non-interactive.

## Arguments

```text
/maintain [--dry-run]
```

`--dry-run` names the stage and the next step, and runs nothing.

## Step 1 — Read the state

```sh
scripts/daily-check.sh --hours 24 > temp/maintain-state.json     # read-only; every key below comes from it
scripts/ej.sh status                                             # tunnels and clusters
ls -t temp/daily-check-*.md temp/afk-handover-*.md 2>/dev/null | head -4
gh issue list --label afk-ok --state open --json number,title,assignees --jq 'length'
```

A key that answers `{"error": …}` is not checked. Say so, and decide the stage from the rest.

## Step 2 — The stage

The first row that holds decides. Each row names its evidence; report it with the stage.

| #   | Holds when                                                                           | Next step                                         | This command |
| --- | ------------------------------------------------------------------------------------ | ------------------------------------------------- | ------------ |
| 1   | `release_main` is red, or `ej.sh status` shows a cluster or pod not Ready            | The blocker, with the failed step                 | reports      |
| 2   | No `temp/daily-check-<today>.md`                                                     | `/daily-check`                                    | runs         |
| 3   | Today's report has a draft heading ending `(unfiled)`                                | "File which?", with each draft's `afk-ok` verdict | asks         |
| 4   | The newest `temp/afk-handover-*.md` has a pull request in **Merge order** still open | `/afk merge`                                      | runs         |
| 5   | An open `afk-ok` issue no session holds, and no handover from today                  | `/afk`                                            | runs         |
| 6   | `release.commits` holds a `feat`, `fix` or `perf` subject                            | The cut, as two commands for the operator         | proposes     |
| 7   | `after_deploy` has a released step whose `due` has come (below)                      | `/post-release`                                   | runs         |
| 8   | None of the above                                                                    | "Nothing due", and the next date anything is due  | reports      |

- **Stage 3**: list the drafts, one line each with `afk-ok: yes|no — reason`, and stop. When the operator answers, file exactly those, as
  [`/daily-check`](daily-check.prompt.md) Step 6 says: the label from the verdict, the board in one batch, the heading rewritten to `(filed #N)`.
- **Stage 5**: "no session holds" is [`/start-issue`](start-issue.prompt.md) step 2's three signals: board Status, a remote branch, an open pull
  request. `/afk` checks them again; this row only decides whether starting it is worth a turn.
- **Stage 6**: give `gh workflow run cut-release.yml -f dry_run=true` for the highlights, then `-f dry_run=false`, and the subjects that make it a
  product release. A release cut less than two hours ago has not settled: stage 7 waits for the rollout and the first imports, so it skips to stage 8.
- **Stage 7**: a step's `due` has come when it is `now`, a date on or before today, `deploy+<N>d` with N days passed since the change reached that
  environment, or a dark day that is today. [`/post-release`](post-release.prompt.md) § When a step is due says how to read each.
- **Stage 8**: the next date is the earliest future `due`, or the next dark day of a `dark-day` step.

## Step 3 — Run or hand over

Run the step when the stage says _runs_, under its prompt. Then go back to Step 1 once, and end with the stage that is now next. Do not run a second step
in the same invocation: the operator reads each result before the next one starts.

Under `/loop /maintain`, a step that waits on something outside the session — CI, a rollout, a release window — ends the turn with a wakeup matched to it,
not a poll. Stages 3 and 6 end the loop's turn with the question, and stage 8 sleeps until the next due date or the next morning, whichever comes first.

## Step 4 — The report

Three lines, then the step's own report:

1. **Stage** and its evidence: "4 — `/afk merge`: #2801 and #2803 open in `temp/afk-handover-2026-10-06.md`".
2. **Ran** or **next**: what ran and its outcome, or the one action for the operator.
3. **After that**: the stage that follows, so the operator knows what the next `/maintain` will do.

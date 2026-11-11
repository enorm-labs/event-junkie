# Maintain

One entry point for the maintenance loop: read where the loop stands, name the one step that is next, and run it when it is a step this command may
run. The steps are the existing commands; this command only joins them.

```text
/daily-check → file the drafts → (/refine-issues) → /afk → /afk merge → cut a release → /post-release
  → (weekly) /musicbrainz-review, /artist-cleanup, /enrich → (monthly) /data-quality-audit, re-date limitations → (idle) /next-importer → again
```

## Important

- **Invoking this command is permission to run the steps marked _runs_ below**, each under its own prompt's rules and nothing more. `/daily-check` merges
  the bot pull requests its Step 6 allows and runs `/worklist` (which applies no fix), `/afk` opens draft pull requests, `/post-release` runs the due
  steps it finds.
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
ls -t temp/daily-check-*.md temp/worklist-*.md temp/admin-review-*.md temp/afk-handover-*.md 2>/dev/null | head -6
ls -t temp/musicbrainz-review-production-*.tsv temp/artist-cleanup-production-*.json temp/promoter-cleanup-production-*.json \
  temp/enrich-*.md temp/data-quality-*.md 2>/dev/null                 # the dated steps: when each last ran
gh issue list --label afk-ok --state open --json number,title,assignees --jq 'length'
gh pr list --state merged --search 'in:title "re-date accepted limitations"' --json number,mergedAt --limit 1   # stage 13
```

A key that answers `{"error": …}` is not checked. Say so, and decide the stage from the rest.

## Step 2 — The stage

The first row that holds decides. Each row names its evidence; report it with the stage.

| #   | Holds when                                                                                                                   | Next step                                         | This command |
| --- | ---------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------- | ------------ |
| 1   | `release_main` is red, or `ej.sh status` shows a cluster or pod not Ready                                                    | The blocker, with the failed step                 | reports      |
| 2   | No `temp/daily-check-<today>.md`                                                                                             | `/daily-check`                                    | runs         |
| 3   | Today's `daily-check`, `worklist` or `admin-review` report has a draft heading ending `(unfiled)`                            | "File which?", with each draft's `afk-ok` verdict | asks         |
| 4   | The newest `temp/afk-handover-*.md` has a pull request in **Merge order** still open                                         | `/afk merge`                                      | runs         |
| 5   | An open `afk-ok` issue no session holds, and no handover from today                                                          | `/afk`                                            | runs         |
| 6   | `release.commits` holds a `feat`, `fix` or `perf` subject                                                                    | The cut, as two commands for the operator         | proposes     |
| 7   | `after_deploy` has a released step whose `due` has come (below)                                                              | `/post-release`                                   | runs         |
| 8   | No `temp/musicbrainz-review-production-*.tsv` from the last 7 days, and the tunnel is up                                     | `/musicbrainz-review production`                  | runs         |
| 9   | No `temp/artist-cleanup-production-*.json` from the last 7 days, and the tunnel is up                                        | `/artist-cleanup production`                      | runs         |
| 10  | No `temp/promoter-cleanup-production-*.json` from the last 7 days, and the tunnel is up                                      | `/artist-cleanup production --kind promoters`     | runs         |
| 11  | No `temp/enrich-*.md` from the last 7 days                                                                                   | `/enrich`                                         | runs         |
| 12  | No `temp/data-quality-*.md` from the last 30 days, and the tunnel is up                                                      | `/data-quality-audit`                             | runs         |
| 13  | No `re-date accepted limitations` PR merged in the last 30 days, and the newest nightly-reports issue has STILL SILENT lines | Re-date the STILL SILENT rows (below)             | runs         |
| 14  | No open `afk-ok` issue, and no `temp/refine-*.md` from the last 7 days                                                       | `/refine-issues` on the next milestone            | proposes     |
| 15  | A 🔨 Ready venue in `docs/EVENT_DATA_SOURCES.md`, and no open `importer` pull request                                        | `/next-importer`                                  | proposes     |
| 16  | None of the above                                                                                                            | "Nothing due", and the next date anything is due  | reports      |

- **Stage 3**: list the drafts, one line each with `afk-ok: yes|no — reason`, and stop. When the operator answers, file exactly those, as
  [`/daily-check`](daily-check.prompt.md) Step 7 says: the label from the verdict, the board in one batch, the heading rewritten to `(filed #N)`.
- **Stage 5**: "no session holds" is [`/start-issue`](start-issue.prompt.md) step 2's three signals: board Status, a remote branch, an open pull
  request. `/afk` checks them again; this row only decides whether starting it is worth a turn.
- **Stage 6**: give `gh workflow run cut-release.yml -f dry_run=true` for the highlights, then `-f dry_run=false`, and the subjects that make it a
  product release. A release cut less than two hours ago has not settled: stage 7 waits for the rollout and the first imports, so it skips to stage 15.
- **Stage 7**: a step's `due` has come when it is `now`, a date on or before today, `deploy+<N>d` with N days passed since the change reached that
  environment, or a dark day that is today. A `when:<text>` step has come only when its text holds. [`/post-release`](post-release.prompt.md)
  § When a step is due says how to read each.
- **Stage 8**: the review writes a file of proposals and applies nothing. Applying waits for the operator's word, as that prompt says.
- **Stages 9 and 10**: the full sweep. `/daily-check` judges only the rows the last day minted, so a parser that has drifted shows here first. Its
  fixes are pull requests. A MERGE waits for the operator's word.
- **Stage 11**: after stage 10, because a row that is not a promoter wastes a search. It opens a pull request and writes nothing to a cluster.
- **Stage 12**: read-only. It writes its report and files nothing.
- **Stage 13**: the nightly `/plausibility-check` re-checks the oldest accepted limitations (#4828), and this stage moves the dates it confirmed (#4841).
  Collect every `SOURCE/ASPECT` line under _Limitation re-check_ → STILL SILENT in the comments of the newest `Plausibility check — nightly reports` issue
  and the one before it, each with the date of the report that saw it. Write them as `SOURCE/ASPECT YYYY-MM-DD` to `temp/limitation-redate-<today>.txt`,
  then run `python3 scripts/limitation-verified.py temp/limitation-redate-<today>.txt` and, when nothing is refused, again with `--apply`. Regenerate
  `docs/data-quality/ACCEPTED_LIMITATIONS.md` with the `cp` that `AcceptedLimitationsTest` prints, run `scripts/format-markdown.sh`, and open the pull
  request with [`/open-pr`](open-pr.prompt.md), titled `chore(importer): re-date accepted limitations`, listing the rows and linking the report comments.
  A NOW PUBLISHED row is not re-dated: it is a defect draft, and `/daily-check` Step 7 files it. A refused pair is a report row that names no declaration;
  quote it in the pull request and leave it out.
- **Stages 14 and 15**: each asks something of the operator or takes an hour or more, so this command names it and does not run it.
- **The dated stages read the newest file in `temp/`.** Deleting a report makes its step due again, which is the safe way round.
- **Stage 16**: the next date is the earliest future `due`, the day a weekly or monthly stage is due again, or the next dark day of a `dark-day` step.

## Step 3 — Run or hand over

Run the step when the stage says _runs_, under its prompt. Then go back to Step 1 once, and end with the stage that is now next. Do not run a second step
in the same invocation: the operator reads each result before the next one starts.

Under `/loop /maintain`, a step that waits on something outside the session — CI, a rollout, a release window — ends the turn with a wakeup matched to it,
not a poll. Stages 3, 6, 14 and 15 end the loop's turn with the question, and stage 16 sleeps until the next due date or the next morning, whichever comes first.

## Step 4 — The report

Three lines, then the step's own report:

1. **Stage** and its evidence: "4 — `/afk merge`: #2801 and #2803 open in `temp/afk-handover-2026-10-06.md`".
2. **Ran** or **next**: what ran and its outcome, or the one action for the operator.
3. **After that**: the stage that follows, so the operator knows what the next `/maintain` will do.

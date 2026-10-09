# Refine Issues

Take open issues one at a time, with the user present, and make each one workable by [`/afk`](afk.prompt.md): correct what has gone stale, ask the
user the one question that keeps it from `afk-ok`, write the answer into the body, and add the label. The interactive half of the loop that `/afk` runs
unattended.

Usage: `/refine-issues Phase 2 — Coverage & polish` takes the open issues of that milestone. `/refine-issues 313 412` takes those issues, in that
order. `/refine-issues` alone takes the first milestone on the launch path that has open issues, and otherwise `Phase 2`. Append `max <n>` to stop
after _n_ issues.

## Important

- **The user is present, and every issue ends on their answer.** This is the opposite of `/afk`: a question is the point, not a failure. Ask with the
  question tool where there is one, one issue per turn, and never two issues in one question.
- **Invoking this command is permission to edit the issues in the queue**, after the user answers for that issue: the body, the labels `afk-ok`,
  `blocked`, `needs-decision` and `size:*`, and the board's Status and Priority. Nothing else. **Never** close an issue, change its milestone, file a
  new one, assign one, branch, commit or start the work. Propose those and leave them to the user, [`/new-issue`](new-issue.prompt.md) or
  [`/milestone-plan`](milestone-plan.prompt.md).
- **The `afk-ok` conditions are [`/new-issue`](new-issue.prompt.md) step 4.** Do not restate them here or loosen them. An issue that fails one after
  the user's answer does not get the label.
- **Verify against the tree and the API, never against the issue text.** The five staleness checks are [`/milestone-plan`](milestone-plan.prompt.md)
  §2. An `afk-ok` issue with a false premise sends an unattended run into a wrong change.
- **The decision goes into the body.** `/afk`'s subagent reads the issue with `gh issue view`, which shows the body and not the comments. A choice
  settled only in a comment is a choice the run does not see.
- **The state file is the state.** Write each outcome to `temp/refine-<scope>-<YYYY-MM-DD>.md` before the next issue. After a compaction, read it first.

## Steps

### 1 · Build the queue

```sh
gh issue list --milestone '<title>' --state open --limit 100 \
  --json number,title,labels,assignees,createdAt --jq 'sort_by(.number)[]'
scripts/generate-backlog-snapshot.sh            # build/BACKLOG.md: Status and Priority per row
```

Read each issue the user named with `gh api repos/{owner}/{repo}/issues/<n>` instead: search can miss a recent change. Leave out, and record as
**skipped** with the reason:

- an issue that already has `afk-ok`, unless the user named it. A named one is checked again, and loses the label if it fails.
- an issue assigned to someone else, or held by another session by the three signals of [`/start-issue`](start-issue.prompt.md) step 2.
- an issue in this scope's state file from today with an outcome. Continue the file; do not ask twice.

Order the rest by Priority (`P0` first, none last), then by number. Open the state file with the queue, one line per issue: `#<n> <title> — queued`.

### 2 · Prepare one issue

Read the issue in full: body, comments, the Links footer, and every file, function and ADR it names. Then:

1. **Run the staleness checks** of [`/milestone-plan`](milestone-plan.prompt.md) §2 on this issue: closed blockers, a blocker handed off, numbers and
   paths in the body, work already landed (`git log -S`), a done-when item made moot. Note the command that proves each finding.
2. **Read the board against the labels** for this issue, by the table in [`/milestone-plan`](milestone-plan.prompt.md) §3.
3. **Check each `afk-ok` condition** of [`/new-issue`](new-issue.prompt.md) step 4, and pick the lane from [`/afk`](afk.prompt.md) § Lanes. Record
   each condition as pass or fail, with the line of the body or the file that decides it.
4. **Sort the issue into one outcome:**

    | Outcome    | Holds when                                                          | The question                                  |
    | ---------- | ------------------------------------------------------------------- | --------------------------------------------- |
    | Ready      | Every condition passes, after the staleness corrections             | "Add `afk-ok`?", with the corrections         |
    | Answerable | Each failing condition is a choice the user can make now            | The choice, the recommended option first      |
    | Human      | A condition fails for good: legal, privacy, irreversible, a cluster | "Leave it for a person?", or `needs-decision` |
    | Split      | `size:XL`, or the done-when holds two PRs                           | The parts, one line each                      |
    | Wrong      | The premise is false, or the work has landed                        | "Close it?", with the proof                   |

**Prepare the next issue while the user answers.** Start one background subagent (`Explore`, read-only) with steps 2.1 to 2.4 for the next issue in
the queue, and have it return the outcome, the evidence and a draft question. Check its evidence before you ask: a subagent's verdict is a claim
too.

### 3 · Ask

One question per issue, at most three choices, the recommended one first. Above the question, in a few lines: the issue's number and title, the
outcome, the staleness findings, and the conditions that fail. Back each option with its effect: what `/afk` will then build, and for a product choice
how many rows on production it touches (the public API answers that). Always offer **Skip** — the issue stays as it is, and the state file says so.

Show the text the body will gain with the question, so the user approves the wording and not only the choice.

### 4 · Apply the answer

```sh
gh issue view <n> --json body --jq .body > temp/refine-body-<n>.md     # check it is not empty before you edit it
# append the block below, then:
gh issue edit <n> --body-file temp/refine-body-<n>.md
gh issue edit <n> --add-label afk-ok --remove-label blocked
```

- **Append a block to the body; do not rewrite the original.** Both the premise and its correction stay readable, as
  [`/milestone-plan`](milestone-plan.prompt.md) §7 says. The block:

    ```markdown
    ## Refined <YYYY-MM-DD>

    - **Decided:** <the option, and what the work does because of it>.
    - **Corrected:** <the stale claim> — <what is true now>, `<command that proves it>`.
    - **Done when** (replaces the list above where they differ): <each item, checkable without the user>.
    ```

    A second refinement rewrites this block in place; it never adds another.

- **Check an empty read before writing.** A `gh` read that returns nothing and a `--body-file` that writes it wipe the body.
- **Correct both sides of a dependency**, as [`/milestone-plan`](milestone-plan.prompt.md) §7 says.
- **Add `afk-ok` only when every condition now passes.** Re-read the body you wrote against [`/new-issue`](new-issue.prompt.md) step 4 first. An
  `- [ ] <env>: <verb>` line under `## After deploy` is what lets a cluster step pass.
- **Board changes wait for the end.** Write each one to `temp/refine-board-<date>.txt` as `<n> Ready` or `<n> Blocked`, and send them in one batch in
  step 5.
- Pace the edits: `sleep 0.45` between `gh` calls.

Then write the outcome to the state file, `#<n> — afk-ok` or `#<n> — skipped: <reason>`, and go on to the next issue.

### 5 · End

- Stop at the end of the queue, at `max`, or when the user says stop.
- `scripts/issue-board.sh batch temp/refine-board-<date>.txt`.
- Finish the state file with the counts, then `scripts/format-markdown.sh temp/refine-<scope>-<date>.md`. Delete `temp/refine-body-*.md`.
- Report, in this order: the issues now `afk-ok` and their lanes, the ones proposed for closing or splitting, the ones left for a person, and the next
  command, `/afk`.

## Notes

- **A short answer beats a complete one.** If an issue needs three questions, it is probably a split or a decision issue. Say so instead of asking all
  three.
- **"Check by eye" can often become a check.** A test, a `curl` against the public API, `scripts/pr-screenshots.sh`. Propose the check that replaces it,
  as one of the options.
- **Findings outside the queue go to [`/new-issue`](new-issue.prompt.md).** Do not widen an issue to hold them.
- **Order is [`/milestone-plan`](milestone-plan.prompt.md)'s job.** Run it first on a milestone that has not been planned. This command makes the
  ordered issues workable, and leaves the order alone.
- **Other sessions work the same backlog.** Check the claim signals again just before you edit an issue.

# AFK

Work a queue of issues while the user is away: one draft PR per issue, the decisions written down where the user will review them, and a handover document
that is current after every PR.

Usage: `/afk 313 412 418` works those issues, in that order. `/afk` alone works every open issue labelled `afk-ok`, oldest first. Append `max <n>` to stop
after _n_ pull requests, or `until <HH:MM>` to stop starting new issues at that time.

## Important

- **This skill is the user's permission to commit, push and open _draft_ pull requests** for the issues in the queue, and nothing beyond that. It does not
  extend to any other issue, and it ends when the queue does.
- **Never, in AFK mode:** merge a PR, mark one ready for review, arm auto-merge, approve or review one, close or comment on an issue, cut a release, run
  `tofu`, `flux`, `helm install` or anything against a cluster, run `sudo`, force-push a branch this run did not create, or start an issue outside the
  queue. Work that needs one of these is **parked** (see below), never worked around.
- **Nothing waits for the user.** A question that needs the user goes into the PR and the handover, and the run moves on. The run never ends on a question.
- **The handover file is the state.** Context is summarized during a long run, and a summary loses detail. After a compaction, read
  `temp/afk-handover-<date>.md` before doing anything else. It says which issues are done, parked and next.
- **Two lanes.** The heavy lane runs one issue at a time, in this checkout: any issue whose gates need Gradle, `scripts/dev-env.sh` or
  `/importer-smoke`. Those share ports, the dev database and the Gradle daemon, and parallel gates flake. The light lane runs up to three issues at once,
  each in its own worktree: docs, scripts, workflows, prompts and frontend. See [Lanes](#lanes).

## Steps

1. **Build the queue.**

    ```sh
    gh issue list --label afk-ok --state open --json number,title,labels,assignees --jq 'sort_by(.number)[]'   # only when no numbers were given
    ```

    The list comes from search, which can miss a label added in the last minutes. Read each issue the user named with
    `gh api repos/{owner}/{repo}/issues/<n>` instead. Drop, and record as **skipped** with the reason: an issue assigned to someone else, one another
    session holds, or one labelled `blocked`, `needs-decision`, `needs-deployment` or `size:XL`. These are the same checks as [`/start-issue`](start-issue.prompt.md) step 2. An issue another
    session holds is **skipped: claimed by another session**. The assignee cannot show that claim, because every session runs as the user, so read
    the three signals in that step: board Status, a remote branch and an open PR. An empty queue ends the run: write the handover and stop.

2. **Check the session can run unattended.** The working tree is clean and `gh auth status` passes. If the tree is dirty, stop and say so. A dirty tree
   belongs to the user and goes into no branch.

3. **Open the handover** at `temp/afk-handover-<YYYY-MM-DD>.md`, with the structure in [The handover](#the-handover). If the file exists from earlier the
   same day, continue it.

4. **Sort the queue into lanes**, then start one subagent per issue (`general-purpose`, `run_in_background`). A fresh context per issue keeps the main
   session small. The main session holds only the queue and the handover. Give the subagent the issue number, the base to branch from, its lane, and
   this brief:

    > Work issue #N to a draft PR, unattended. The user is away and answers nothing.
    >
    > 1. Follow `.github/prompts/start-issue.prompt.md` steps 1–6: read, check, claim, branch, plan. Branch with
    >    `git fetch origin && git checkout -b <type>/<N>-<slug> <base>`; `git checkout main` fails in a worktree. Skip step 7: no approval comes.
    > 2. Implement. Run the `/verify` gates the diff touches (`.github/prompts/verify.prompt.md`), except the Playwright e2e suite: CI runs it.
    >    Run `/importer-smoke` for an importer change. A parity script without an argument fixes files; pass `check`.
    > 3. Open the PR by `.github/prompts/open-pr.prompt.md`, with `--draft`. Add `--base <parent-branch>` if the base is not `origin/main`. Add these
    >    sections after `## What and why`, and delete each one that is empty:
    >     - `## Decisions I made`: one line each. What I chose, the alternative, how to reverse it.
    >     - `## Open questions`: what the user has to answer before this can merge.
    >     - `## Stacked on`: `#<parent PR>`. Merge that one first.
    > 4. Do not watch the checks. After the PR opens, run `git checkout --detach origin/main` and report the checks as pending.
    >
    > Decide reversible choices yourself: take the option that changes less, and write it under `## Decisions I made`. If the issue needs something
    > irreversible, a product or legal call, a cluster, or a Privacy & GDPR category from AGENTS.md, park it: open no PR. Leave the branch pushed if it holds
    > useful work. Never: merge, mark ready, auto-merge, close or comment on an issue, run tofu/flux/cluster commands, start another issue.
    >
    > Light lane: you are in your own worktree. First run `ln -s <main checkout>/events-frontend/node_modules events-frontend/node_modules`. Run no
    > Gradle task, no `scripts/dev-env.sh`, no dev server and no Playwright suite. If the work turns out to need one, stop and report "heavy".
    > The exception is `scripts/pr-screenshots.sh`: it serves its own builds on free ports. A change a visitor can see gets its before and after
    > screenshots on the PR, by `/open-pr` step 6. Do not leave them as an open question.
    >
    > Report: PR URL or "parked", the branch and its head sha, the decisions, the open questions, and the reason if parked.

5. **After each subagent returns, update the handover**, then start the next issue in that lane. Only the main session writes the handover. Check the
   stop conditions before each start: `max`, `until`, and an empty queue. An issue reported "heavy" goes back into the heavy lane's queue.

6. **Check CI on every PR of this run, once, after the last issue.** Nothing waits for CI per issue: CI takes about eight minutes, and the next issue
   starts while it runs.

    ```sh
    gh pr checks <n> --json name,bucket,link --jq '.[] | select(.bucket != "pass" and .bucket != "skipping")'
    ```

    - Pending: wait with the loop from [`/open-pr`](open-pr.prompt.md) step 8, in the background.
    - Red: read the log (`gh run view <run-id> --log-failed`). A GitHub outage, such as a 5xx or "No server is currently available", gets
      `gh run rerun <run-id> --failed` and no commit.
    - Red because of the change: one subagent per PR checks out the branch, fixes, amends and pushes with `--force-with-lease`. After two failed fixes,
      the PR goes into the handover as red, under "Needs you". A stacked child goes after its parent.

7. **End the run.**
    - `git checkout --detach origin/main` so the checkout holds no branch of this run. Remove each light-lane worktree with `git worktree remove`; its
      branch is pushed. Stop anything this run started: `scripts/dev-env.sh down`, and every
      background watcher.
    - Finish the handover's summary, then run `scripts/format-markdown.sh temp/afk-handover-<date>.md`.
    - Send a push notification: "AFK run done: <n> PRs, <m> parked. Handover: temp/afk-handover-<date>.md".
    - Print the handover as the final message.

## Lanes

Sort by the files the issue will change, from its body, its `area:*` labels and the code it names.

| Lane  | Files                                                                                          | At once | Where             |
| ----- | ---------------------------------------------------------------------------------------------- | ------- | ----------------- |
| Heavy | `events-core/`, `events-bff/`, `events-importer/`, `detekt-rules/`, `*.gradle.kts`, migrations | 1       | This checkout     |
| Light | `docs/`, `scripts/`, `.github/`, `events-frontend/`, `deploy/` rendering only, any `.md`       | 3       | One worktree each |

- **When unsure, heavy.** A wrong heavy costs time. A wrong light can break another lane's gate.
- **Two issues that change the same file run one after the other, in one lane.** Stack the second if it needs the first; otherwise wait for the first
  PR to open.
- **A worktree shares the runtime.** Ports `8080`, `8081`, `5173` and `4173`, the dev Postgres and the Docker daemon are the same for every checkout.
  That is why the light lane runs none of them. `scripts/pr-screenshots.sh` is safe in it, because it takes free ports and reaches only production's API.

## Stacked pull requests

Stack only when an issue needs code from a PR of this run that is not merged yet. Otherwise branch from `origin/main`, even when the issues are related.

- The child branches from the parent's branch, and its PR targets that branch (`--base`). Both PRs name each other: the child under `## Stacked on`,
  the parent in one line under `## What and why`.
- **Three deep at most.** A fourth issue that needs the stack is parked with "needs #<top> merged".
- If the parent merges before the child's PR opens, the child rebases onto `origin/main` with `git rebase --onto origin/main <parent-head>` and
  targets `main`.
- After the user merges a parent, the child needs `git rebase --onto origin/main <parent-head>` and a PR base change. AFK mode does not do that. Put it in
  the handover as a step for the user, or for the next run.

## Parking

Park an issue when it cannot be finished without the user. Park; do not guess. Record it in the handover: what was done, the branch if one was pushed,
and the exact question. Leave the issue assigned and set its board status back: `scripts/issue-board.sh status <n> Todo`. An unassigned card with a pushed
branch reads as abandoned work.

## The handover

The one document the user reads on return. Lead with what needs the user. Rewrite it after each issue, and do not only append.

```markdown
# AFK run <date>, <start>–<end>

## Needs you

1. #<PR> — <the question, answerable in one line>
2. Parked #<issue> — <what is missing>

## Pull requests

| Issue | PR    | Checks | Stacked on | Decisions to review |
| ----- | ----- | ------ | ---------- | ------------------- |
| #313  | #2501 | green  | —          | 1                   |

## Parked and skipped

- #418 parked: <reason>. Branch `fix/418-…` pushed, no PR.
- #420 skipped: `needs-decision`.

## After you merge

- Rebase #2503 onto `main` once #2501 is in.
```

## Notes

- **Labelling for a run**: `gh issue edit <n> --add-label afk-ok`. The label means "may be worked unattended". AFK mode never adds or removes it.
- **Other sessions work the same backlog.** Just before claiming, check again for a claim by another session, using the signals in
  [`/start-issue`](start-issue.prompt.md) step 2, not the assignee. An issue claimed meanwhile is skipped.
- **A long gate is not a hang.** `Backend build` takes about eight minutes in CI and longer locally. Check `docker ps` and the test-result timestamps
  before calling a gate dead.
- Fewer permission prompts make a longer run: a prompt nobody answers stops the run where it is. Run `/fewer-permission-prompts` before the first run.

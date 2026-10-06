# Open PR

Take the current work from a clean working tree to an open pull request in one go: branch, commit with a Conventional Commits message, push, and open the PR.
This is the standard "ship it" flow — the manual equivalent of
"create a branch, commit with `/commit-message`, and open a PR".

## Important

- Always run git commands with the pager disabled (`git --no-pager …` or `GIT_PAGER=cat`) to avoid hanging on interactive output — see AGENTS.md.
- This skill **is** the explicit user permission to commit and push (AGENTS.md otherwise forbids unsolicited commits/pushes). Don't invoke it on your own
  initiative.
- Never commit directly to `main`. If the working changes are on `main`, always cut a new branch first.
- The `gh` CLI is installed and authenticated — use it to open the PR.

## Steps

1. **Inspect the state.** Run `git status` and `git --no-pager diff` (plus `git --no-pager diff --staged` if anything is already staged) to see what changed. If
   the tree is clean with nothing to commit, stop and say so. Use the conversation history for the _why_ behind the change — it usually carries motivation the
   diff alone doesn't.

2. **Sanity-check the changes.** Skim the diff for anything that shouldn't ship: stray debug output, secrets, commented-out code, unrelated edits, leftover
   scratch files. If something looks out of place, flag it to the user before committing rather than sweeping it in.

3. **Create the branch.** Derive a short, descriptive branch name from the change using the Conventional Commits type and scope, e.g.
   `feat/events-venue-filter`, `fix/importer-null-price`, `docs/agents-pr-flow`.
    - If currently on `main`: `git checkout -b <type>/<slug>`.
    - If already on a non-`main` feature branch: assume it's the intended branch and reuse it (mention this). Only ask if the branch name clearly doesn't match
      the current change.
    - **If the change is a _new_ one and you are still on the last one's branch, go back to `main` first.** `git checkout -b` from a feature branch stacks the
      new branch on the old one, so the PR carries the parent's commits too and its diff is wider than the change — a reviewer approves files you never meant
      to put in front of them. It is easy to miss precisely because the PR still says "into main" and merges cleanly. Recovery once the parent has merged is
      `git rebase --onto origin/main <parent-head>`, then force-push. Happened on 2026-08-08: #249 carried #248's whole frontend i18n change.

4. **Stage the changes.** Stage the files that belong in this PR (`git add …`; `git add -A` is fine when the whole tree is the change). Leave out anything you
   flagged in step 2 unless the user wants it in.

5. **Write the commit message.** Follow the [commit message prompt](commit-message.prompt.md): Conventional Commits 1.0.0, an imperative subject under the
   type/scope, and a body explaining the _what_ and _why_ (body lines are not capped at 72 chars in this repo). Commit via `git commit -F -` with a heredoc so
   the multi-line message and trailers stay intact. Include any commit trailers your harness requires (e.g. a `Co-Authored-By:` line).

6. **Push.** `git push -u origin <branch>` to set upstream on first push.

7. **Open the PR.** Use `gh pr create` targeting `main`. Title = the commit subject. **The body is
   [`.github/pull_request_template.md`](../pull_request_template.md), filled in**: `--body` replaces the template, so read it and keep its headings in its
   order. Pass the body via `--body "$(cat <<'EOF' … EOF)"` to preserve formatting, and append any PR footer your harness requires.

    - `## What and why`: what changed and why; for a larger change, a short list.
    - `## Checks`: the `/verify` result block, or what ran instead, or "not tested" with the reason. Never claim a check that did not run.
    - **Delete every section that does not apply**, as the template's comments say. An empty `## After deploy` fails `label-pr.yml`.
    - `## Privacy & legal`: tick the box, or name the AGENTS.md § Privacy category the change is in and what this PR updates for it.
    - `## Contributor Licence Agreement`: delete it. The section is for a contributor from a fork; the maintainer's own PRs, and an agent's working for
      the maintainer, do not carry it.
    - **If the change closes an issue, put `Closes #<n>` in the PR body** — on its own line, near the top of `## What and why`. This repo allows only
      **Rebase and merge** (squash and merge commits are both disabled), so a closing keyword in a commit message would work too — but the PR body is one line
      to fix when the number changes, whereas a commit means rewriting history, and it survives the amending a branch goes through during review. Use `Closes`
      rather than `Fixes`/`Resolves`, one line per issue.
    - **Set the milestone to the issue's own** (`gh pr edit <pr> --milestone '…'`). Every closed PR in this repo carries a milestone — the 255 that predate the
      tracker were backfilled into `Phase 0 — Foundation` — and a PR without one is the exception that makes the milestone view stop meaning anything.
    - **If a visitor can see the change, attach before and after screenshots.** Take them with `scripts/pr-screenshots.sh <path>...`: before is the base
      (`--base`, default `origin/main`), after is the branch. It builds both, serves each on a free port with production's API, and prints a before and
      after table. It starts no dev server, so it runs in any worktree. Put the table under the template's `## Screenshots` and pass each referenced
      `./build/pr-screenshots/<file>.png` with `--attach`, from the repository root; `gh` uploads the file and rewrites the link. `--click` opens a menu,
      `--scroll` brings a list end into view, and `--label` keeps a second run from overwriting the first. A design rule change needs the picture
      ([design.instructions.md](../instructions/design.instructions.md) § How a rule leaves this list). Mechanics: the [`gh` skill](../../.claude/skills/gh/SKILL.md).
    - **Say what has to happen after it deploys**, if anything: a forced import so a parser fix reaches stored rows, a check that a migration ran, a
      script to run. Write it under `## After deploy` in the format [`/post-release`](post-release.prompt.md) reads, one `- [ ]` line per step.
      A step that needs a window or a quiet day says when it is due: "over 3 days" becomes `production (3 days after deploy):`, and a re-key
      that collides with today's rows becomes `both (dark day <slug>):`. Without that, every `/post-release` run before the date reports it again.
      `label-pr.yml` sets the `after-deploy` label from an unticked step, and fails a section with no step. The queue then lives on the pull request, so no
      session has to stay open waiting for the release.
    - **Move the issue on the board**: `scripts/issue-board.sh status <n> 'In review'`. Merging the PR closes the issue, and the board follows — but that
      last part is a **project setting, not a property of GitHub**, so it is worth knowing where it lives. The `Item closed` workflow (Project → ⋯ →
      Workflows) is what sets `Status: Done`. It was silently off until 2026-08-18, and the failure mode is quiet in both directions: closed issues keep
      sitting in `In review`, and nobody looks, because this instruction tells you to stop here. **If a card does not move after a merge, check that workflow
      before assuming anything about the issue.**

8. **Watch the checks in the background.** Start the watcher right after `gh pr create`, as a background task (in Claude Code, `run_in_background`), and
   carry on; the notification brings you back when CI settles. Do not call the PR green, and do not hand it over as done, before that notification arrives.

    ```sh
    until gh pr checks <n> --json bucket --jq 'length > 0 and all(.bucket != "pending")' 2>/dev/null | grep -qx true; do sleep 30; done
    gh pr checks <n>
    ```

    - **Do not use `gh pr checks --watch`.** It exits 0 when its connection drops, so a dropped network reads as a finished run. The loop above treats a
      failed call as "not yet", and `length > 0` waits out the seconds before the first check registers.
    - **A row can stay `pending` after its job finished.** If one check is the only thing still pending long after the others, read its job:
      `gh api repos/{owner}/{repo}/actions/jobs/<id> --jq '.status, .conclusion'`, with `<id>` from `gh pr checks <n> --json name,link`. `gh run rerun <id>`
      clears a stuck row.
    - **On red**, read the failing job's log (`gh run view <run-id> --log-failed`), say which check failed and why, and fix it on the branch — amend and
      `--force-with-lease`, then start the watcher again.
    - **The watcher only observes.** No checkout, commit or push inside the background task; those stay in the foreground.
    - `Backend build` is the long pole, at about eight minutes. Everything else reports inside a minute.

9. **Report.** Print the branch name, commit subject, the PR URL that `gh` returns, and the check result — or that the watcher is still running.

## Notes

- **Verify before shipping (optional but encouraged).** If the change touches code (not just Markdown), consider running [`/verify`](verify.prompt.md) — or at
  least the relevant subset — before step 5, and record the outcome in the PR's `## Checks` section. If the user asked to skip verification, honour that but say the
  PR is unverified.
- **Multiple logical changes.** If the diff spans clearly unrelated concerns, say so and offer to split them across commits (or PRs) rather than bundling
  everything into one.
- **Re-running on an existing PR branch.** If the branch already has an open PR, don't open a duplicate — **amend the existing commit**, push it with
  `git push --force-with-lease`, bring the PR title and body back in step with the diff, and start the step 8 watcher again. A branch normally lands as one commit here, because `main` allows
  only **Rebase and merge** and replays every commit as written. See AGENTS.md § Agent Instructions.
- **Draft PRs.** Add `--draft` when the user wants early feedback or CI signal before the work is final.

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

7. **Open the PR.** Use `gh pr create` targeting `main`. Title = the commit subject. Body should have a short **Summary** (what and why) and a **Testing**
   section stating what was verified (tests run, `/verify` results, or
   "not tested" if nothing was run — never claim tests that didn't run). Append any PR footer your harness requires. Pass the body via
   `--body "$(cat <<'EOF' … EOF)"` to preserve formatting.

    - **If the change closes an issue, put `Closes #<n>` in the PR body** — on its own line, near the top of the Summary. This repo allows only
      **Rebase and merge** (squash and merge commits are both disabled), so a closing keyword in a commit message would work too — but the PR body is one line
      to fix when the number changes, whereas a commit means rewriting history, and it survives the amending a branch goes through during review. Use `Closes`
      rather than `Fixes`/`Resolves`, one line per issue.
    - **Set the milestone to the issue's own** (`gh pr edit <pr> --milestone '…'`). Every closed PR in this repo carries a milestone — the 255 that predate the
      tracker were backfilled into `Phase 0 — Foundation` — and a PR without one is the exception that makes the milestone view stop meaning anything.
    - **Move the issue on the board**: `scripts/issue-board.sh status <n> 'In review'`. Merging the PR closes the issue, and the board follows — but that
      last part is a **project setting, not a property of GitHub**, so it is worth knowing where it lives. The `Item closed` workflow (Project → ⋯ →
      Workflows) is what sets `Status: Done`. It was silently off until 2026-08-18, and the failure mode is quiet in both directions: closed issues keep
      sitting in `In review`, and nobody looks, because this instruction tells you to stop here. **If a card does not move after a merge, check that workflow
      before assuming anything about the issue.**

8. **Report.** Print the branch name, commit subject, and the PR URL that `gh` returns.

## Notes

- **Verify before shipping (optional but encouraged).** If the change touches code (not just Markdown), consider running [`/verify`](verify.prompt.md) — or at
  least the relevant subset — before step 5, and record the outcome in the PR's Testing section. If the user asked to skip verification, honour that but say the
  PR is unverified.
- **Multiple logical changes.** If the diff spans clearly unrelated concerns, say so and offer to split them across commits (or PRs) rather than bundling
  everything into one.
- **Re-running on an existing PR branch.** If the branch already has an open PR, don't open a duplicate — commit and push the follow-up, then point at the
  existing PR.
- **Draft PRs.** Add `--draft` when the user wants early feedback or CI signal before the work is final.

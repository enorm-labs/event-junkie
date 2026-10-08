# Git Cleanup

Removes the worktrees and local branches whose work is finished. Worktrees pile up from `git worktree add`, `claude --worktree` and `isolation: worktree`
agents, and branches pile up because a rebase merge leaves every merged branch looking unmerged. This command sorts both into **safe to remove**, **ask
first** and **keep**, and removes only what the operator confirms.

## Important

- **Read-only until the operator answers.** Steps 1 to 3 change nothing. Step 4 removes only the items the operator confirmed, one command each.
- **Other sessions work in the same repository at the same time.** A worktree that looks idle can be another session's work in progress. Anything with
  uncommitted changes, unpushed commits, an open PR or a live lock is **keep** or **ask first**, never **safe**.
- **`git branch --merged` is useless here.** `main` allows only Rebase and merge, so a merged branch's tip is never an ancestor of `main`. Decide by the PR
  state, and by `git cherry` for a branch without a PR.
- **Never delete a remote branch.** GitHub deletes a merged PR's branch itself. Deleting the head branch of an open PR closes the PR.
- **Never discard work.** No `git worktree remove --force` on a dirty worktree, no `git branch -D` on a branch with a `+` line in `git cherry` and no merged
  PR, no `git stash drop`. Such an item goes to **ask first** with what would be lost.
- **Never touch the current worktree, the main checkout or `main`.**
- `git --no-pager`, `gh` non-interactive. Run each removal on its own, never chained.

## Step 1 — Refresh

```sh
git fetch --prune origin
git worktree prune --dry-run --verbose   # metadata for directories deleted by hand
```

## Step 2 — Worktrees

```sh
git worktree list --porcelain
```

For each worktree except the main checkout and the current one, collect:

```sh
git -C "$wt" status --short --branch            # dirty? ahead of upstream?
git -C "$wt" log -1 --format='%cr %s'           # last commit
gh pr list --head "$branch" --state all --json number,state,mergedAt --limit 5
```

- **`locked`**: `claude agent … (pid N start …)` names the agent process. `ps -p N -o lstart=` shows whether it still runs. A live PID with the same start
  time is **keep**. A dead PID is an orphaned agent worktree: classify it by its contents like any other, and unlock it before removal.
- **Detached HEAD**: compare `HEAD` with `origin/main` by `git cherry origin/main HEAD`. No `+` line means nothing is lost.
- **A directory under `.claude/worktrees/` that `git worktree list` does not show** is not a worktree any more. List it under **ask first** with its size
  (`du -sh`); it may hold untracked files.

## Step 3 — Branches

```sh
git for-each-ref refs/heads --format='%(refname:short)|%(upstream:short)|%(upstream:track)|%(worktreepath)|%(committerdate:relative)'
```

Skip `main` and every branch checked out in a worktree: Step 2 covers those. For each other branch:

| Evidence                                                                          | Class         |
| --------------------------------------------------------------------------------- | ------------- |
| PR `MERGED`                                                                       | **safe**      |
| No PR, and `git cherry origin/main <b>` prints no `+` line                        | **safe**      |
| PR `OPEN`                                                                         | **keep**      |
| PR `CLOSED` unmerged, or no PR with `+` lines in `git cherry`                     | **ask first** |
| Upstream exists and is not `[gone]`, branch ahead of it                           | **ask first** |
| `worktree-agent-*` branch with no worktree, no upstream and `+` lines in `cherry` | **ask first** |

For an **ask first** branch, show what would be lost: `git --no-pager log --oneline origin/main..<b>` and the PR title.

## Step 4 — Report, then remove

Lead with the verdict, then the three lists. Every line carries its evidence.

```text
Git cleanup: 5 safe, 2 ask first, 3 keep

SAFE — nothing is lost
  1. worktree ../event-junkie-tresor  [feat/tresor]   clean, PR #2412 MERGED
  2. branch fix/old-thing                              PR #2390 MERGED
  3. branch worktree-agent-a09d20f49d4de3d23           no PR, git cherry: 0 commits not on main

ASK FIRST
  4. branch new-findings                               PR #2101 CLOSED unmerged, 2 commits not on main
  5. .claude/worktrees/agent-ae3c420946e7ccc78         not registered, 410 MB

KEEP
  agent-a108f4514a53c0057   locked, pid 56025 alive
  venue-map-overlapping-pins  PR #2801 OPEN
```

Then stop and wait. The operator answers with numbers, `safe`, or `all`. For each confirmed item, in this order:

```sh
git worktree unlock "$wt"            # only for a lock whose PID is dead
git worktree remove "$wt"            # no --force; a refusal means it is dirty — report it
git branch -D "$branch"              # -D because a rebase-merged branch fails -d
git worktree prune
```

A refusal is a finding, not an obstacle: report it and move on. Never retry with `--force`. End with the counts before and after: `git worktree list | wc -l`
and `git branch | wc -l`.

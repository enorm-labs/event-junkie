# Wrap Up

The last command before a session closes. It reads everything the session touched and sorts it into what is finished, what still needs a step now, and what
carries on after the session. Nothing that exists only in this conversation survives it: a watcher dies with the session, a promise made in chat is forgotten,
and an uncommitted file in a worktree nobody opens again is lost.

## Important

- **Read-only until the operator answers.** This command changes nothing on its own: no commit, no push, no issue, no merge, no deleted file, no stopped
  process. It ends with a list of proposed actions. Carry out the ones the operator confirms, one by one.
- **The conversation is the primary source, and it is the one that compacts.** Read the whole visible conversation, including a compaction summary, before any
  command. A command can show that a branch is dirty. Only the conversation shows that you said "I'll dispatch the dry run once it merges".
- **Report the state you measured, not the state you remember.** Every line in the report carries the command that produced it, or the message it came from.
  A PR you remember as open may have merged an hour ago: other sessions and the operator work the same repository.
- **A background task never runs git.** When a check needs a commit, a push or a branch switch, it goes on the action list for the foreground.
- `git --no-pager`, `gh` non-interactive.

## Step 1 — What the conversation promised

Go through the conversation from the first message. List each of these, with where it came from:

- **Every request from the operator**, and whether it is done, partly done, or not started. A request narrowed or deferred along the way counts as not done
  unless the operator agreed to the narrowing.
- **Every promise you made**: "I'll do X when Y", "after the merge", "next I will". These are the items most easily lost, because nothing outside the
  conversation records them.
- **Every question you asked that has no answer**, and every decision you took on a default because nobody answered.
- **Every finding you mentioned and did not file**: a neighbouring bug, a stale sentence in a document, a flaky test. Each one is an issue draft for
  [`/new-issue`](new-issue.prompt.md), or a line saying why it needs none.
- **Every check deferred to a later date**: "7 green nights", "measure from 2026-10-13". Record the date and the check.

## Step 2 — What is still running

Background work belongs to the session and stops when it closes.

- **Background shells, `Monitor` watchers, cron jobs and scheduled wakeups** started in this session. For each one: what it waits for, whether that happened
  (check the condition directly), and what it was going to do next. A watcher that has not fired is a promise from Step 1 that nothing will now keep.
- **Local runtime**, only if the session started it:

    ```sh
    scripts/dev-env.sh status                 # importer, BFF, Vite, Postgres
    scripts/ej.sh status                      # tunnels and port-forwards to the clusters
    pgrep -fl 'kubectl.*port-forward'         # forwards started by hand, outside ej.sh
    k3d cluster list                          # a rehearsal cluster left behind
    docker ps --format '{{.Names}}\t{{.Status}}'
    ```

    A process the session did not start is not yours to stop. Say it runs, and leave it.

- **A temporary change on a cluster**: a suspended Flux object, a scaled-down deployment, a drill's NetworkPolicy. Each one must be reverted or handed over by
  name.

## Step 3 — What git holds

Check every worktree, not only the current one. A session that ran `git worktree add` or an `isolation: worktree` agent can leave work elsewhere.
Other sessions work in other worktrees at the same time: report a worktree this session did not touch in one line, and leave it.

```sh
git worktree list
git worktree list --porcelain | awk '/^worktree /{print $2}' | while read -r wt; do
    echo "== $wt"; git -C "$wt" status --short --branch
done
git for-each-ref --format='%(refname:short) %(upstream:short) %(upstream:track)' refs/heads | grep -E 'ahead|gone'
git stash list --format='%gd %cr %gs'
ls temp/
```

- **Uncommitted or untracked files**: part of the work, a scratch file to delete, or a file the operator changed. Ask when unsure. Never discard one.
- **A branch ahead of its upstream**: a commit that was never pushed.
- **A branch whose upstream is `[gone]`**: merged and deleted remotely. Check its PR state before calling it merged, because a rebase merge hides it from
  `git branch --merged`.
- **A stash this session created**: apply it or drop it by its SHA, never by position. Other sessions share the stash stack.
- **A plan in `temp/`**: delete it when its PR merged, keep it while the branch is open. AGENTS.md § Agent Instructions has the rule.

## Step 4 — What GitHub holds

```sh
gh pr list --author @me --state open --json number,title,headRefName,autoMergeRequest,milestone --limit 50
gh issue list --assignee @me --state open --json number,title,milestone --limit 50
```

For each PR the session opened or changed:

- **CI**: `gh pr checks <n>`. A pending check is not a green one. A red one is an action.
- **Merge state**: auto-merge armed or not, and whether that was the operator's choice. Never arm it on your own.
- **Housekeeping from [`/open-pr`](open-pr.prompt.md)**: a milestone, a closing line in the body, and a board status of `In review`. A PR that must not close
  its issue says `Part of #N`, never a negated closing keyword.
- **After a merge**: the issue closed or deliberately left open, the board card moved, the post-merge check done.

For each issue the session claimed with [`/start-issue`](start-issue.prompt.md): a PR exists, or the issue goes back to `Ready` with its assignee removed, or
the plan says why it stays `In progress`. Read the board with `scripts/issue-board.sh show <n>`.

## Step 5 — What the repository and memory should now say

- **Documentation**: did a change in this session make a document wrong? AGENTS.md § Agent Instructions has the rule to correct it in the same change.
- **Memory**: a fact that was not obvious and cost time, which the repository does not record. Propose it with its **Why** and **How to apply**. A memory
  proven wrong in this session is deleted, not kept beside a correction. Anything the repository now records is not a memory.

## Output

Lead with the verdict, then three lists. Every line carries its evidence.

```text
Wrap-up: 2 open, 1 handover, nothing lost

OPEN — needs a step before the session closes
  1. PR #2190 CI pending (Backend build)         gh pr checks 2190
  2. temp/2102-agent-docs-no-subagents.md        PR #2190 not merged yet — keep

HANDOVER — carries on after the session
  1. Dispatch agent-docs.yml dry run on main after #2190 merges   promised in chat; watcher dies with the session
  2. #2102 done-when: 7 green scheduled runs, ~2026-10-07        gh run list -w agent-docs.yml

DONE
  #2102 claimed, PR #2190 open with milestone and In review       scripts/issue-board.sh show 2102
```

Then the **proposed actions**, numbered, each one a single command or tool call. Stop and wait for the operator.

**A handover is only safe where the next session will find it.** For each HANDOVER item, propose where it goes: a comment on its issue or PR, a new issue, or
a memory for a fact rather than a task. A handover that exists only in this report is lost when the terminal closes.

# Parallel work with Git worktrees

A [git worktree](https://git-scm.com/docs/git-worktree) is a second working directory on its own branch, sharing the repository's `.git` directory and remote.
Two worktrees means two checkouts whose files cannot collide — which is what makes it practical to run two coding sessions, or two AI agents, on two importers
at the same time.

Background: [Claude Code: run parallel sessions with worktrees](https://code.claude.com/docs/en/worktrees) ·
[Git worktrees for parallel AI coding](https://www.mindstudio.ai/blog/git-worktrees-parallel-ai-coding-agents).

## What is and isn't isolated

**Isolated:** source files, the branch, Gradle `build/` output.

**Not isolated:** the local runtime. Postgres (host port `56298`), the importer (`8081`), the BFF (`8080`) and the frontend (`5173`) are fixed, shared,
single-tenant resources.

So the rule is: **edit in parallel, run the stack one worktree at a time.**

## 1. Create a worktree

Claude Code can create and enter one for you. This puts a fresh checkout in `.claude/worktrees/tresor` on a new branch `worktree-tresor`, branched from
`origin/main`, and starts the session there:

```bash
claude --worktree tresor
```

Run it again with another name in a second terminal for a second isolated session. (You can also just ask Claude to
"work in a worktree" mid-session.)

**For work that ends in a pull request, plain git is usually nicer**, because you pick the branch name that
`/open-pr` will push — it reuses the branch it finds rather than cutting a new one, so a `--worktree` session would open its PR from `worktree-tresor`:

```bash
git worktree add ../event-checker-tresor -b feat/tresor origin/main
cd ../event-checker-tresor
claude
```

`.claude/worktrees/` is gitignored, so Claude-created worktrees never show up as untracked files in the main checkout.

IntelliJ can do the same from the UI ([JetBrains docs](https://www.jetbrains.com/help/idea/use-git-worktrees.html)):

- **Create** — Git tool window (<kbd>⌘9</kbd>) → **Worktrees** → **New Worktree**, or **Git | New Worktree**. Pick the source branch (`origin/main`), a project
  name and a location *outside* this repository, e.g.
  `../event-checker-tresor`. The worktree opens as its own project window. The same branch cannot be checked out in two worktrees, so give each one a new
  branch.
- **Switch** — double-click a worktree in the **Worktrees** tab; or right-click a branch in **Log** → **Open Worktree**.
- **Remove** — select it in **Worktrees** and click **Delete** (not possible for the main or currently open worktree, and commit first). A directory deleted by
  hand shows as *Prunable* — **Prune** clears them.

The usual caveat about `.idea/workspace.xml` making IntelliJ treat every worktree as one project does not apply here: all of `.idea` is gitignored. Each
worktree window therefore needs its own SDK and run configurations — see the next two steps.

## 2. Set the worktree up

A worktree checks out tracked files only, so each one needs its own environment:

```bash
sdk env                                  # .sdkmanrc is tracked — this just works
cd events-frontend && npm ci             # only if you need the frontend; dev-env.sh refuses to start it without node_modules
```

- Gradle's `build/` directories are per worktree, so the first build there compiles from scratch.
- The gitleaks pre-commit hook lives in the shared `.git` directory and is therefore **already active** in every worktree — no second `pre-commit install`.
- This repo has no gitignored-but-required files (no `.env`), so no
  [`.worktreeinclude`](https://code.claude.com/docs/en/worktrees) is needed. Add one only if that changes.

## 3. Point the worktree at the existing database

**This is the one step that bites.**

Docker Compose derives its project name from the directory holding `compose.yaml`, and both `bootRun` and
`scripts/dev-env.sh` pass the *worktree's* copy. So a worktree at `.claude/worktrees/tresor` would come up as compose project `tresor` — a second Postgres
container on a brand-new empty `tresor_postgres-data` volume, clashing with the main checkout on host port `56298`. An empty database also makes `diff-snapshot`
report every existing source as `GONE`.

Export the main checkout's project name in every worktree shell, and compose reuses the running container, its volume and its seeded data instead:

```bash
export COMPOSE_PROJECT_NAME=event-checker

# with it     →  Container event-checker-postgres-1  Running        (reused, data intact)
# without it  →  Volume tresor_postgres-data  Creating              (empty DB, and port 56298 is already allocated)
```

Put it in the worktree's shell profile, a direnv `.envrc`, or the IntelliJ run configuration — anywhere it is guaranteed to be set before the first `bootRun`.

## 4. Take turns on the stack

Everything that only touches files runs in parallel across as many worktrees as you like: writing scrapers, fixtures and unit tests, `ktlintFormat`, `detekt`,
`:events-importer:test`. Everything with a port or a row in the database is serialised:

- **Only one worktree may hold the stack.** `scripts/dev-env.sh down` in the first, then `up` in the second —
  `dev-env.sh` overrides such as `IMPORTER_HOST` only change the URL it polls, not the port the JVM binds, so a second importer cannot simply move to `8091`.
- **`bootRun` does not hot-reload**: whichever worktree started the JVM is the code being smoke-tested. Restart after switching.
- **Never let two worktrees import at once.** `snapshot` / `diff-snapshot` count events per source across the whole database, so the other session's import
  lands in your regression diff as an unexplained delta.
- Two concurrent Gradle builds mean two daemons at `-Xmx2g` each — two or three active worktrees is a sane ceiling on a laptop.

## 5. Expect conflicts in the shared files

One worktree = one venue = one PR (that is exactly the `/next-importer` contract). Every importer PR touches the same handful of shared files, so resolve these
**deliberately** rather than accepting either side:

| File                                         | What conflicts                                                                                               |
|----------------------------------------------|--------------------------------------------------------------------------------------------------------------|
| `docs/EVENT_DATA_SOURCES.md`                 | the status **count** table plus the moved row — recount after rebasing; both sides bump the same numbers     |
| `http/importer/dev-seed.http`                | the alphabetical header list and the venue block — "keep both" can silently fuse two blocks; rebuild by hand |
| `events-importer/.../scraper/EventSource.kt` | one new enum entry each                                                                                      |
| *(none — file an issue)*                                    | a smoke-test finding goes to the tracker, not to a file                                                             |

**Rebase feature branches onto `main`; don't merge `main` into them** — PRs here are merged with "Rebase and merge", which a merge commit blocks.

## 6. Clean up

```bash
git worktree list
git worktree remove ../event-checker-tresor   # add --force if it still holds uncommitted work
git worktree prune                            # drop metadata for directories deleted by hand
```

`git worktree remove` deletes the directory but keeps the branch. Claude's own exit prompt for a `--worktree`
session offers to remove the branch too, so **decline it unless the work is pushed or merged**. Sessions started with `-p` are never cleaned up automatically.

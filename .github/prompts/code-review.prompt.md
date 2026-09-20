# Code Review

Review the changes in this pull request (or diff) for correctness, maintainability and adherence to project conventions.

## Context

1. `gh pr view` for the description, linked issues and conversation; the intent decides what "correct" means.
2. `git --no-pager diff origin/main...HEAD` (or `gh pr diff`) for the whole diff; `gh pr checks` before reading code.
3. Read the full files around every hunk — a change reviewed in isolation misses what it broke next door.
4. The conventions are the path-scoped rules, and they load as you open the files: [kotlin](../instructions/kotlin.instructions.md),
   [architecture](../instructions/architecture.instructions.md), [logging](../instructions/logging.instructions.md), [testing](../instructions/testing.instructions.md),
   [vue](../instructions/vue.instructions.md), [design](../instructions/design.instructions.md), [comments](../instructions/comments.instructions.md),
   [kubernetes](../instructions/kubernetes.instructions.md), [ci-cd](../instructions/ci-cd.instructions.md). Review against them; do not restate them.

## What to look for

**Correctness first.** Does the code do what the PR claims? Off-by-one, races, null paths, empty collections; a blocking call inside a suspending path; a
`@Query` that interpolates a request value; a migration edited in place; a new `@TestPropertySource` that forks a Spring context; a selector-label change
that installs and fails the second release.

**Then the things the rules cannot lint**, because they are judgement:

- **Tests for the change.** A changed `@Service`, controller, scraper or composable without a test change is a finding. `./gradlew koverHtmlReport` for touched
  classes; coverage must not regress on a touched file.
- **The comment diff.** Was a comment rewritten, or appended to? A "note: also…" clause on a behaviour change is the defect only the diff shows. Flag a
  comment that restates the code, carries history or dates, copies an ADR instead of pointing at it, or has document structure in it. **Never ask for a
  long comment recording a trade-off to be deleted** — ask for fewer words. Review a `.tf`, `.sh`, `.yaml` or `.py` comment as strictly as a KDoc; that is
  where the volume is.
- **The document diff.** Flag a doc change that _appends_ where it should have _replaced_: an "Update:" note, a dated banner, a paragraph beside the one it
  supersedes, a phase marked done rather than deleted, reasoning above the instructions. **Does the change make any document wrong?** The one to check is
  rarely in the diff — status banners, `## The short version` blocks, the Key Files table.
- **Logging as part of the change** — a caught-and-continued path at `WARN` with the exception as the argument, a per-line value as a payload, a new field name
  in all three places. **A privacy trigger** (AGENTS.md § Privacy & GDPR) named in the PR description when the change hits one.
- **A new dependency justified**, centralised (`gradle.properties` / `settings.gradle.kts` / exact pin in `package.json`), and not one a bot owns.
- **A new convention** written into the rule that owns it, in the same PR.

## Output

Per finding: **file and line**, **severity** — 🔴 blocker, 🟡 suggestion, 🟢 nit — **what and why**, and **a concrete fix**, not "fix this". Group by
file, blockers first. If the code is good, say so; do not invent findings.

**Post the review as a pull-request comment**: `gh pr comment <n> --body-file <file>`. Never `gh pr review --comment` — a review by the maintainer's own account
is the latest review the merge gate reads, and it has knocked an approval off a green PR. Write nothing under `docs/`; a review is not documentation.

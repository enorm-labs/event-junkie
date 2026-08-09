# The backlog manifest

**This directory is temporary.** It exists to move the 179-item `TODO.md` backlog into GitHub Issues
as a reviewable change, and it is deleted once the migration is finished. The plan is
[docs/GITHUB_ISSUES_MIGRATION.md](../../docs/GITHUB_ISSUES_MIGRATION.md).

After the migration, **GitHub Issues is the backlog.** Nothing here is a source of truth.

---

## What is in here

One markdown file per issue, `NNNN-<slug>.md`, plus `.created.json` mapping slugs to issue numbers.

The **numeric prefix is the creation order**, so issue numbers come out in rough priority order and
`#1 … #N` reads roughly as the backlog. Reordering priority is a `git mv`, which git records as a
rename — a one-line diff instead of a moved block.

```
0010-0080   v0.2 — Deployable
0100-0280   v0.3 — Launch-ready
0300-0430   v1.0 — Go-live
0500-0995   Phase 2 — Coverage & polish
1000-1030   Phase 3 / Phase 4 epics, and one unscheduled issue
```

## File format

YAML front matter, markdown body.

```markdown
---
slug: importer-bug-late-night-drop     # unique, stable, kebab-case; must match the filename tail
title: A late-night club event is dropped at midnight
type: Task | Bug | Feature             # the org's GitHub issue types
milestone: v0.2 — Deployable           # omit for the unscheduled backlog
labels: [importer, "area:data-quality", "size:M"]
priority: P0 | P1 | P2                 # project field, not a label
status: Backlog                        # project field; defaults to Backlog
parent: some-epic-slug                 # attaches as a GitHub sub-issue, one level only
related: [other-slug]                  # rendered into a Links footer
blocked-by: [other-slug]               # rendered into a Links footer
---

The body starts here and is posted as the issue body, with a generated Links footer appended.
```

**Quote any label containing a colon.** A bare colon inside a YAML scalar is a parse error —
`area:data-quality` unquoted is not a string, it is a syntax error. `validate` catches it, but the
message is yq's rather than a helpful one.

**The body stays outside the YAML on purpose.** It is the bulk of every file and it is markdown:
outside, editors lint and render it, code fences and nested lists need no re-indenting, and a body
edit produces a clean one-issue diff.

## Working with it

```sh
scripts/backlog-sync.sh validate            # parse + check everything. Runs in CI.
scripts/backlog-sync.sh plan                # what apply would create and update (the default)
scripts/backlog-sync.sh preview --only SLUG # the exact body that would be posted
scripts/backlog-sync.sh apply --limit 5     # create the first five, then look before continuing
scripts/backlog-sync.sh apply               # create or update all of them
scripts/backlog-sync.sh link                # resolve cross-links, attach sub-issues
scripts/backlog-sync.sh project             # add to the board, set Status and Priority
scripts/backlog-sync.sh report              # the slug -> issue-number mapping
```

Needs `gh`, `yq` and `jq`. `BACKLOG_OFFLINE=1 … validate` skips the checks that need the API, which
is how it runs without credentials.

## Two properties worth knowing

**Idempotent.** `.created.json` is written after *every* issue, not at the end, and is committed. A
slug that already has a number is **updated**, never recreated — so an interrupted run resumes where
it stopped, and re-running is safe.

**`apply` refuses to run on a dirty `.created.json`.** An uncommitted lockfile means a previous run
was interrupted and its result was never reviewed; applying on top would bury whatever went wrong.

## Conventions the bodies follow

Extracted from `TODO.md`, whose entries carry *why*, *what it costs* and *what it is blocked on*.
That prose is the asset — it is why this migration is an extraction rather than a rewrite.

- **Keep the specifics.** File and function names, real example strings from the venue, the blast
  radius as a number. `7 artist rows today, 5 of them VOID Club's` is worth more than "several".
- **Importer defects state whether a `--full` re-seed is needed.** It is usually the difference
  between a one-hour change and a one-day one.
- **Record what was decided against, and why.** A choice that is not written down is rediscovered as
  an oversight and "fixed".
- **Story format only where there is a user.** Infrastructure and parser repairs get
  problem → impact → fix instead.

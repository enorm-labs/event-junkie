# Migration plan — TODO.md → GitHub Issues

**Status: complete.** Decided and executed 2026-08-09.

| PR | | |
|---|---|---|
| 1 | ✅ | 7 milestones, 16 labels, the project, 5 issue templates, 255 closed PRs backfilled into `Phase 0` |
| 2 | ✅ | the 146-issue manifest and `backlog-sync.sh` |
| 3–4 | ✅ | issues **#258–#403** created, cross-linked, sub-issued, on the board |
| 5 | ✅ | `TODO.md` deleted · backlog snapshot generated · all references repointed |
| 6 | ✅ | `/new-issue`, `/next-issue`, `/start-issue`, `scripts/issue-board.sh`, `Closes #N` in `/open-pr` |

**One cleanup deliberately left to a separate decision:** `.github/backlog/`, `scripts/backlog-sync.sh` and
`.github/workflows/validate-backlog.yml` are migration scaffolding that has served its purpose. Deleting them removes a stale
copy of the backlog that someone could edit expecting it to sync; keeping them preserves the pre-split record and the
slug → issue mapping outside git history. **This document goes with them whenever that is decided** — though §§7–10 record
things that cost real time to learn and are worth reading once first.

Moves the backlog out of [TODO.md](../TODO.md) and into GitHub Issues, Milestones and one Project, without losing the thing that makes TODO.md valuable: each
entry carries *why*, *what it costs*, and *what it is blocked on*. That prose is the asset — the migration is mostly a careful extraction of it, not a rewrite.

[VISION_ROADMAP_IDEAS.md](VISION_ROADMAP_IDEAS.md) **stays**. It is a direction document, not a task list; it gets repointed at milestones instead of at TODO.md
sections. Only the Someday/Vision *themes* become issues, as epics.

---

## Decisions taken

| Question          | Decision                                                                                                                                                                    |
|-------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Granularity       | **Curated, ~110–120 issues** — not the raw 179 checkboxes. Trivial siblings merge, nested items become sub-issues, Someday/Vision becomes ~6 epics.                         |
| TODO.md           | **Deleted**, replaced by a `build/BACKLOG.md` snapshot generated on demand, so agents keep a grep-able view.                                                            |
| Milestones        | **Release-named near term** (`v0.2`, `v0.3`, `v1.0`), **phase-named far term** (Phase 2/3/4). Phase 1 is 38 mixed items and would burn down meaninglessly as one milestone. |
| Existing PRs      | **All 255 closed PRs → `Phase 0 — Foundation`**, which is then closed. There are no open PRs.                                                                               |
| Issue type axis   | **GitHub issue types** (`Task` / `Bug` / `Feature`, already defined on the `enorm-labs` org), not a `type:` label axis.                                                     |
| Priority & status | **Project fields**, not labels — they churn, and label churn is noise.                                                                                                      |
| Area & size       | **Labels** — intrinsic to the issue, visible in every list and in `gh issue list`.                                                                                          |

### The rule behind the label/field split

> **Intrinsic properties of the work → labels. Planning state → Project fields.**

`area:` and `size:` describe what the work *is* and do not change once set. Priority and status describe where it sits in your head this week. Keeping the
second kind out of labels is what stops the tracker filling with relabel noise.

---

## Prerequisites

**Done 2026-08-09.** The `github.com` token needed the Projects scope, which only the account holder can grant:

```
gh auth refresh -s project,read:project
```

Everything else (labels, milestones, issues, issue types, sub-issues) worked with the scopes already granted.

---

## 1. Milestones

Seven milestones — **created 2026-08-09**, numbers 1–7 in creation order. Rationale for splitting Phase 1: it holds 38 items ranging from "write the Helm
chart" to "check a link preview in WhatsApp", and a single progress bar over that mix tells you nothing about whether you can launch.

| Milestone                              | What it holds                                                                                                                                                                                                                                                          | Roughly |
|----------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------|
| `Phase 0 — Foundation`                 | Historical. All 255 closed PRs. Closed immediately after assignment.                                                                                                                                                                                                   | —       |
| `v0.2 — Deployable`                    | ADR-012 accepted, domain registered, Terraform/OpenTofu, Helm chart, CI/CD workflows, staging stage, frontend container, local k3d exercise                                                                                                                            | ~9      |
| `v0.3 — Launch-ready`                  | Postgres backups + rehearsed restore, monitoring/alerting, auth & authz, BFF caching, API protection, Dependabot cleanup, the legal go-live blockers (address, mailboxes, Art. 28 contracts, privacy re-check, legal review, both-language read-through)               | ~20     |
| `v1.0 — Go-live`                       | The deploy itself, go-live checklist, per-environment `robots.txt`/`sitemap.xml`, meta-injection transport, Search Console setup, Rich Results test, link-preview check, Lighthouse against the live origin, indexing watch, hero screenshot, beta badge off + `1.0.0` | ~14     |
| `Phase 2 — Coverage & polish`          | Importer defects, data-quality pillars, more importers, admin tooling, frontend/BFF features, UI/UX/branding, ops hardening, docs & agent tooling                                                                                                                      | ~65     |
| `Phase 3 — Accounts & personalization` | Epics only                                                                                                                                                                                                                                                             | ~3      |
| `Phase 4 — Social & ecosystem`         | Epics only                                                                                                                                                                                                                                                             | ~3      |

**No Phase 5 milestone.** "Expand beyond Berlin" is one epic with no milestone. **No milestone at all** is the unscheduled backlog —
`is:issue is:open no:milestone` is the query for it.

---

## 2. Project

One org-level project, **"Event Junkie"** — [orgs/enorm-labs/projects/1](https://github.com/orgs/enorm-labs/projects/1), public, linked to the repo.
**Created 2026-08-09.** Reasons for exactly one: a second project means deciding which one an issue belongs to every time you file, and there is one product.

**Fields** (beyond the built-ins):

- **Status** (single select): `Backlog` · `Ready` · `In progress` · `In review` · `Blocked` · `Done`
- **Priority** (single select): `P0 — now` · `P1 — next` · `P2 — later`

Three levels, not five. "Critical" and "high" both mean *do it now*, and a five-level scale collapses to three in practice anyway.

**Views** — all four created with their name, layout and filter:

| View | Layout | Filter |
|---|---|---|
| **Roadmap** | table | `is:open` |
| **Board** | board | `is:open` |
| **Ready to pick up** | table | `is:open no:assignee -label:blocked -label:needs-decision -label:needs-deployment` |
| **Blocked** | table | `is:open label:blocked,needs-decision,needs-deployment` |

*Blocked* exists so the 19 deployment-blocked items never read as neglected work — the same job §At go-live & after does in TODO.md today. *Ready to pick up*
is what `/next-issue` will read.

> **Manual step left.** `ProjectV2ViewConfigurationInput` exposes only `visibleFieldIds` — **grouping and sorting cannot be set through the API**, by CLI or
> GraphQL. Four settings to apply once in the UI, each a few seconds:
> - *Roadmap* → group by **Milestone**, sort by **Priority**
> - *Board* → group by **Status**
> - *Ready to pick up* → sort by **Priority**, then **Size**
> - *Blocked* → group by **Milestone**
>
> Filters survive; only the visual arrangement is manual. Worth knowing before writing any future script that assumes views are fully declarative.

---

## 3. Labels

### Do not touch the existing ones

`.github/release.yml` groups release notes by PR label, and `.github/workflows/label-pr.yml` applies them from Conventional Commit titles. Renaming `feat`,
`fix`, `docs`, `bug`, `importer`, `build`, `ci`, `chore`, `refactor`, `test`, `dependencies` or `perf` **breaks release notes silently**. They stay exactly as
they are.

Two of them double as issue labels rather than getting a prefixed twin:

- **`importer`** — already applied by the `new-venue` and `wrong-event-data` templates and used by release.yml. No `area:importer`.
- **`documentation`** — no `area:docs`.

The area axis is therefore not perfectly uniform. That is a deliberate trade: release-note compatibility beats naming symmetry.

### New — `area:` (9, blue family `#1d76db`-ish)

| Label               | Scope                                                            |
|---------------------|------------------------------------------------------------------|
| `area:data-quality` | normalization, validation, enrichment, the four pillars          |
| `area:bff`          | public API, `events-bff`                                         |
| `area:frontend`     | Vue app, UX, accessibility, branding                             |
| `area:infra`        | cloud, Kubernetes, Terraform, Helm, deploys, backups, monitoring |
| `area:ci`           | workflows, gates, tooling checks                                 |
| `area:legal`        | GDPR, imprint, copyright, scraping legality                      |
| `area:seo`          | sitemap, meta tags, structured data, indexing                    |
| `area:security`     | Dependabot, Checkov, security workflows, auth                    |
| `area:agents`       | AGENTS.md, prompts, skills, agent workflows                      |

### New — `size:` (4, grey→green gradient)

| Label     | Meaning                                         |
|-----------|-------------------------------------------------|
| `size:S`  | under half a day                                |
| `size:M`  | one to two days                                 |
| `size:L`  | about a week                                    |
| `size:XL` | too big — split into sub-issues before starting |

`size:XL` is a *defect flag*, not an estimate. An issue that keeps it for long is one you are avoiding because it is not really one issue.

### New — state (3, amber `#fbca04` / red `#d93f0b`)

| Label              | Meaning                                                                  |
|--------------------|--------------------------------------------------------------------------|
| `blocked`          | waiting on another issue — the blocker is named in the body              |
| `needs-decision`   | a choice has to be made before work can start (ADR candidates live here) |
| `needs-deployment` | not blocked on effort, blocked on a live origin                          |

`needs-deployment` is the one that carries the most weight in this repo: it is the whole reason §At go-live & after exists as a separate section, and without it
those 21 items look like neglect in any issue list.

### Keep, unchanged

`good first issue`, `help wanted`, `duplicate`, `wontfix`, `question`, `invalid`, `enhancement` — public-repo hygiene, and `enhancement` is in release.yml's
Features bucket.

---

## 4. Issue templates

Your three existing templates are all **outward**-facing — for site visitors reporting bad data or suggesting a venue. There is nothing for your own work. Five
internal templates get added, and the chooser gets reordered by filename so the public ones stay on top.

```
.github/ISSUE_TEMPLATE/
  config.yml
  1-wrong-event-data.yml     (existing, renamed)
  2-bug.yml                  (existing, renamed)
  3-new-venue.yml            (existing, renamed)
  4-task.yml                 NEW
  5-feature.yml              NEW
  6-importer-defect.yml      NEW
  7-decision.yml             NEW
  8-epic.yml                 NEW
```

| Template                                              | Type                           | Fields                                                                                                                                                                                            | Why it exists                                                                                                                                                                                     |
|-------------------------------------------------------|--------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **`4-task.yml`** 🛠 Task                              | `Task`                         | Summary · Why it matters · What "done" looks like · References (files, ADRs, docs) · Risks & notes                                                                                                | The default for migrated items. Most of the backlog is not a user story and should not be forced into one.                                                                                        |
| **`5-feature.yml`** ✨ Feature                        | `Feature`                      | Story (`As a … I want … so that …`) · Why now · Acceptance criteria · Out of scope · Depends on                                                                                                   | Story format **only where there is a user**. "As a user I want Terraform" is noise.                                                                                                               |
| **`6-importer-defect.yml`** 🔍 Importer / data defect | `Bug`                          | Venue(s) & scraper(s) · What the source publishes · What we store today · The code path (file + function) · Proposed fix · Needs a `--full` re-seed + diff? · Blast radius (rows/events affected) | **Highest-value template here.** All 23 Bugs entries already share exactly this shape, and the re-seed question is the one that decides whether a one-line change is a one-hour or a one-day job. |
| **`7-decision.yml`** ⚖️ Decision / ADR candidate      | `Task`, label `needs-decision` | The question · Options · What it blocks · Needs an ADR? · Trigger/deadline                                                                                                                        | You have ~5 of these (curated-vocabulary storage, event series as an entity, seating shape, AI-assisted data quality, anonymous-first accounts). They are not tasks and rot when filed as tasks.  |
| **`8-epic.yml`** 🧭 Epic                              | `Feature`                      | Outcome · Why now · Definition of done · (sub-issues attached after creation)                                                                                                                     | Parents for the Someday themes and the four multi-child items.                                                                                                                                    |

`blank_issues_enabled: false` stays, and `config.yml` keeps all four contact links unchanged — only its header comment gains the ordering and issue-type
conventions, so the next person to add a form knows why the filenames are numbered.

**All eight forms carry `type:`**, including the three public ones (`1-wrong-event-data` → Bug, `2-bug` → Bug, `3-new-venue` → Feature). That was not in the
original plan but follows from the decision to make issue types the type axis: a report arriving from a visitor should be typed the same way as one filed by
hand, or the axis has a hole in it exactly where the unattended traffic comes in.

### Sub-issues, not checklists

GitHub sub-issues replace the 27 nested checkboxes. Four parents earn one:

- **Admin frontend** — 7 children (dashboard kit, imports status & control, import configuration, data-quality overview, data review & fixing, AI-assisted
  checking, manual entry, submission review queue)
- **More importers** — epic + 3 sub-issues; the rest of its nested list becomes a checklist inside the epic body
- **Create more prompts/skills/agents** — one issue with an 8-line checklist, *not* 8 issues. Each child is an hour of work; eight issues for eight hours is
  tracker theatre.
- **Template repository** — one issue with a 3-line checklist

---

## 5. The manifest

Rather than annotating TODO.md in place (a document nobody would read again), the migration is driven by a **reviewable manifest** — one markdown file per
issue, under `.github/backlog/`.

```
.github/backlog/
  README.md              how the manifest and the sync script work
  .created.json          key → issue number lockfile (committed; the idempotency record)
  0100-adr-012-accept.md
  0110-register-domain.md
  0120-terraform-iac.md
  ...
```

The numeric filename prefix **is the creation order**, so issue numbers come out roughly in priority order and `#1 … #N` reads as the backlog. Cheap to do, and
permanently useful.

### File format

**Real YAML front matter, markdown body** — parsed with `yq` (mikefarah v4.53.3, installed 2026-08-09) piped into `jq`:

```sh
yq --front-matter=extract -o=json '.' <file>          # metadata
awk 'NR==1&&$0=="---"{f=1;next} f&&$0=="---"{f=0;b=1;next} b' <file>   # body
```

Both verified working on the installed version. Front matter gets proper typed lists and quoting (`"area:data-quality"` needs the quotes — a bare colon inside a
YAML scalar is a parse error, which is exactly the sort of thing a hand-rolled `key: value` splitter would have swallowed silently).

The body deliberately stays **outside** the YAML rather than in a `body: |` block. It is the bulk of every file and it is markdown: kept outside, editors lint
and render it, code fences and nested lists need no re-indenting, and a body edit produces a clean one-issue diff.

```markdown
---
key: importer-bug-late-night-drop
title: A late-night club event is dropped at midnight while it is still running
type: Bug
milestone: Phase 2 — Coverage & polish
labels:
  - importer
  - "area:data-quality"
  - "size:M"
priority: P1
parent: null
related:
  - importer-bug-no-end-time
blocked-by: []
---

`EventUpsertService.dropPastEvents` compares dates only, so a night the venue lists as
`31/07 23:00` — which actually runs until ~06:00 the next morning — disappears from the app at 00:00, hours before it ends.

**Blast radius.** Every late-opening club (OHM, Berghain, Tresor, Renate, …). OHM feels it hardest because its whole horizon is one to three nights.

**The fix.** A cutoff that accounts for the start time (keep an event until
`eventDate + 1 day 06:00` when it starts after ~22:00) rather than a per-importer workaround.

**Related.** #NNN (no event end time) — the missing field is why this needs a start-time heuristic instead of simply asking whether the event has ended.
```

`related` and `blocked-by` reference other manifest **slugs**; the script resolves them to issue numbers on the linking pass, once every issue exists.

### Why a manifest at all

- **Reviewable** — the whole migration lands as a PR diff you can read, before a single issue is created.
- **Idempotent** — `.created.json` means re-running never duplicates. A slug that already has a number gets *updated*, not recreated.
- **Auditable** — the TODO.md → issue mapping is a committed artefact, not something reconstructed later from memory.
- **Restartable** — a 120-issue run that dies at 70 resumes at 70.

### Why one file per issue, and not one YAML file per section

Now that `yq` is available, a handful of section-sized YAML files (`02-importer-bugs.yml` and friends, each holding 20+ entries) would parse just as cleanly.
One file per issue is still the better shape here, for reasons that have already cost this repo time:

- **Conflicts.** A big block-structured file whose entries all look alike is precisely the `dev-seed.http` failure mode — a keep-both resolution silently fuses
  two entries and nothing downstream complains. Separate files conflict per issue or not at all.
- **Reordering is a rename.** Changing priority means renaming `0430-…md` to `0180-…md`; git records it as a rename and the diff is one line. Moving a 40-line
  block between positions inside a YAML file is a 80-line diff that hides any edit made in passing.
- **Agent safety.** An agent asked to fix one issue body edits one small file, not a surgical replacement inside a 900-line YAML.
- **Review granularity.** GitHub shows per-file review status, so working through ~120 issue drafts in PR 2 has a built-in progress marker.

The manifest directory is deleted in the final PR, once `.created.json` has served its purpose. (Or kept — it is small, and it is the only record of what the
backlog looked like before the split.)

---

## 6. The sync script

`scripts/backlog-sync.sh` — bash + `gh` + `yq` + `jq`, matching `scripts/dev-env.sh`'s existing style. It checks for all four up front and prints an install
hint rather than failing halfway (`brew install yq`); the CI `validate` job installs `yq` explicitly. Subcommands:

| Command    | Does                                                                                                                                                                                                                                              |
|------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `validate` | parses every manifest file; fails on malformed YAML, a missing required field, an unknown label/milestone/type, a dangling `related`/`parent`/`blocked-by` key, or a duplicate key. **Runs in CI** so a malformed manifest never reaches `apply`. |
| `plan`     | dry run — prints exactly what would be created/updated, and nothing else. **The default with no subcommand.**                                                                                                                                     |
| `apply`    | creates or updates issues in filename order; writes `.created.json` after *each* issue, not at the end                                                                                                                                            |
| `link`     | second pass — attaches sub-issues to parents, appends resolved `Related: #N` / `Blocked by: #N` lines. Needs all numbers, so it cannot run in pass one.                                                                                           |
| `project`  | adds every created issue to the Event Junkie project and sets Status + Priority                                                                                                                                                                   |
| `report`   | prints the mapping table for the record                                                                                                                                                                                                           |

Two safety properties worth building in deliberately:

- **`apply` refuses to run on a dirty `.created.json`** — an uncommitted lockfile means a previous run was interrupted and not reviewed.
- **`apply` rate-limits itself** (a short sleep between creates). 120 issue creations plus 255 PR-milestone calls is well inside the secondary rate limit, but
  only if it is not fired as fast as bash can loop.

---

## 7. Scope of the curation — what actually happened

Starting point: 162 checkboxes + 17 Someday bullets = **179**. Target when this plan was written: **~110–120**.

**Actual result: 146.**

> **The estimate was wrong, and it is worth saying why rather than quietly restating it.** The
> per-section arithmetic in the table below sums to roughly 149 — the "~119" total was a bad
> addition on my part, not a plan that was then overshot. The section-level judgements held up
> almost exactly; only the total was wrong.
>
> Reaching 110 from here would have meant merging work items that are genuinely separate — three
> distinct parser bugs into one "artist name cleanup" issue, four legal blockers into "legal
> readiness". That trades a reviewable, closable backlog for a smaller number, and the number was
> never the goal. **146 issues that each map to a pull request beats 110 that do not.**
>
> If the tracker still reads as too large in review, the honest lever is dropping work, not merging
> it — and that is a decision for the review, which is what the manifest is for.

The table below is the plan as written; the "Becomes" column proved accurate per section.

| Source                              | Raw     | Becomes     | How                                                                                                                                                       |
|-------------------------------------|---------|-------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| 🔴 Now                              | 11      | 11          | 1:1, split across `v0.2`/`v0.3`/`v1.0`                                                                                                                    |
| 🟠 Next                             | 4       | 4           | 1:1                                                                                                                                                       |
| UI / UX / Branding                  | 10      | ~9          | 1:1 mostly                                                                                                                                                |
| Frontend & BFF                      | 16      | ~14         | the two venue-type filter items merge                                                                                                                     |
| Importer **Bugs**                   | 23      | **23**      | 1:1 — these are crisp, individually closable, and each maps to a PR. No merging.                                                                          |
| Data quality                        | ~12     | ~11         | 1:1; the four pillars stay separate                                                                                                                       |
| Importer coverage & parsing         | 5       | 5           | 1:1                                                                                                                                                       |
| Admin tooling & maintenance         | 10      | 10          | 1 epic + 7 sub-issues + 2 standalone                                                                                                                      |
| More importers                      | 8       | ~5          | 1 epic + 3 sub-issues; rest become a checklist in the epic                                                                                                |
| Coverage scope (deferred/undecided) | 3       | 3           | all three become `needs-decision`                                                                                                                         |
| Comedy/theatre actionable           | 2       | 2           | 1:1                                                                                                                                                       |
| Operations & Hardening              | 13      | ~12         | 1:1 mostly                                                                                                                                                |
| Legal / Compliance                  | 2       | 2           | 1:1                                                                                                                                                       |
| 🚀 At go-live & after               | 21      | ~19         | 1:1, all tagged `needs-deployment`; the two proofread items merge                                                                                         |
| Tooling, AI Agents & Skills         | 14      | ~5          | the 8 nested skill items collapse to one checklist issue; Repomix / awesome-copilot / BACKLOG.md-approach fold into one "agent tooling exploration" issue |
| Docs, Repo & Templates              | 10      | ~8          | template-repo children become a checklist                                                                                                                 |
| 🔵 Someday / Vision                 | 17      | **6 epics** | see below                                                                                                                                                 |
|                                     | **179** | **146**     | *(the "~119" originally printed here was an addition error; the column sums to ~149)*                                                                     |

**How the 146 actually landed**

| Milestone | Issues |
|---|---:|
| `v0.2 — Deployable` | 8 |
| `v0.3 — Launch-ready` | 19 |
| `v1.0 — Go-live` | 14 |
| `Phase 2 — Coverage & polish` | 98 |
| `Phase 3 — Accounts & personalization` | 3 |
| `Phase 4 — Social & ecosystem` | 3 |
| *(no milestone — unscheduled)* | 1 |

By type: 89 Task, 34 Feature, 23 Bug. By priority: 24 P0, 65 P1, 57 P2.
11 sub-issues under 2 epics; 40 `related` and 41 `blocked-by` cross-references.

**Phase 2 holding 98 is the honest shape**, not a failure of curation — it is the whole post-launch
backlog, and it is exactly what a milestone with no due date is for. The three release milestones
carry 41 between them, which is the number that decides whether launch is reachable.

### The six Someday epics

| Epic                                  | Milestone | Absorbs                                                                                                                                                                                                                                                                                                                 |
|---------------------------------------|-----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Accounts, follows & notifications     | Phase 3   | login/profile, follow/favourite, saved searches, scoped notification rules, favourites, reminders, RSVP, customizable start page, recommendations, **and the "does stage 1 need an account at all?" decision** (which is filed *separately* as a `needs-decision` issue — it has to be answered before the epic starts) |
| User & venue submitted events         | Phase 3   | submission UI + plausibility checks; links to the admin submission-queue sub-issue                                                                                                                                                                                                                                      |
| Social layer                          | Phase 4   | friends, following users, activity timeline, event invites, popularity ranking, collaborative recommendations, ratings & reviews                                                                                                                                                                                        |
| Calendar sync & external integrations | Phase 4   | iCal export, ICS feed per follow/saved search, Spotify/Deezer/SoundCloud/RA, the Facebook Graph API check                                                                                                                                                                                                               |
| Public API & conversational access    | Phase 4   | public API + API management, chatbot / MCP server                                                                                                                                                                                                                                                                       |
| Expand beyond Berlin                  | *(none)*  | Phase 5 in one issue                                                                                                                                                                                                                                                                                                    |

Venue & artist profiles and the club map fold into existing Phase 2 issues (venue enrichment, venues map) rather than becoming Someday epics — they are closer
than the rest of that list.

---

### What the apply run actually taught

Recorded because both are the kind of thing that costs an hour twice.

- **`gh issue create` and `gh issue edit` do not share a label flag.** Create takes `--label`; edit takes `--add-label` / `--remove-label`. One argument list for both aborts on the first *update* — which is invisible until something has already been created, since the create path works fine. Found after 5 issues existed and the 6th run tried to update them.
- **An update has to reconcile labels in both directions.** `--add-label` alone lets a label dropped from the manifest survive on the issue forever, and nothing reports the drift. The script now diffs wanted against current and applies both.
- **The cautious `--limit 5` run was worth it**, and is worth repeating for any future bulk change: it is what turned a 146-issue failure into a 5-issue one.

---

## 8. Existing PRs → `Phase 0 — Foundation`

**Done 2026-08-09.** All 255 closed PRs (0 open, 0 previously milestoned) patched into `Phase 0 — Foundation`, which now reads 255/255 and is **closed** so it
stops appearing in every milestone picker. Verified: no closed PR is left without a milestone.

Two things worth recording for anyone repeating this:

- Closed-unmerged PRs got the milestone too. Separating them is not worth the judgement calls.
- **Pace the loop.** 255 `PATCH` calls fired as fast as bash can loop will trip GitHub's secondary rate limit. A `sleep 0.45` between calls put the run at
  roughly four minutes and zero failures.

---

## 9. Replacing TODO.md for agents — `build/BACKLOG.md`

**This is the piece that decides whether the migration is a net win.** Twenty-three files referenced `TODO.md`, and five prompt files (`importer-smoke`,
`data-quality-audit`, `next-importer`, `security-report`, `codebase-audit`) *instructed agents to read and append to the Bugs list*. Replacing every one of
those with a `gh issue list` call is slower, costs tokens, and fails when the network does.

So `scripts/generate-backlog-snapshot.sh` renders every open issue into a grep-able local file: grouped by milestone, one row each with type, area, size and
blocking state. **Reads stay a local file read; writes go to the API.**

### The plan here was wrong, and the fix is worth recording

The plan said a **workflow** would regenerate the file and commit it on issue open/close/reopen and nightly, keeping a committed `docs/BACKLOG.md` always
current. It was built that way in PR 5, and **it failed on its first real run.**

The `main` ruleset requires every change to arrive by pull request, and its only bypass actor is `OrganizationAdmin`. The workflow pushes directly to `main`, so
the push was rejected: *"Changes must be made through a pull request."*

The obvious fix — add `github-actions[bot]` to the bypass list — **is not available**. GitHub rejects it outright:

> `Actor GitHub Actions integration must be part of the ruleset source or owner organization`

That is a platform constraint, not a permissions problem: the API call had admin rights and failed validation, and the UI offers no such actor either.

**So the snapshot became generate-on-demand**, written to `build/BACKLOG.md` (already gitignored) and never committed. The workflow was deleted.

**This is arguably the better design anyway**, which is the part worth remembering:

- A file regenerated before use **cannot be quietly stale**. The committed one silently was — it was frozen at 146 issues while the tracker had 148, and nothing
  said so.
- No bot commit stream on `main`, and no generated file that can ever appear in a diff or conflict during a rebase.
- The header carries a generation timestamp, so staleness is visible rather than assumed.

The cost is one `gh` call per session and that the file is absent from a fresh clone — so **human-facing docs link to the tracker**, and only agent-facing
instructions (AGENTS.md and the prompts) mention the snapshot at all.

**The lesson generalises:** check the branch protection rules before designing anything that writes to the default branch from CI.

---

## 10. Reference cleanup

The 23 files split into three kinds of work, in ascending difficulty:

**a) Pure link swaps** — `README.md` (2), `docs/PRODUCT_OVERVIEW.md` (3), `docs/EVENT_DATA_SOURCES.md` (2), `docs/EVENT_SCOPE.md` (2),
`docs/LEGAL.md` (2), `docs/BRANDING.md`, `docs/WORKTREES.md`, `perf/README.md`, `perf/load.js`, `docs/adr/ADR-012_CLOUD_PLATFORM.md`,
`docs/DATA_QUALITY_PILLAR_1_PLAN.md`. Point at the tracker, a milestone, or a label query.

**b) Kotlin KDoc pointing at "the TODO.md bugs list"** — `ArtistNameMapping.kt`, `EventUpsertService.kt`, `MorphineDetailPageScraper.kt`,
`HeimathafenWebsiteImporter.kt`, `PeterEdelOverviewPageScraper.kt`. These should reference the **specific issue number**, not a list —
`// Known limitation: see #NNN`. That is a genuine improvement over what is there now, and it only becomes possible after PR 3.

**c) Prompt files whose *instructions* change** — the real work. Five prompts currently tell an agent to append findings to TODO.md:

| Prompt               | Current instruction                                         | Becomes                                                                                      |
|----------------------|-------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| `importer-smoke`     | "repairable defects in `TODO.md` (Bugs)"                    | open an issue from `6-importer-defect.yml`, after checking `build/BACKLOG.md` for a duplicate |
| `next-importer`      | "record it in `TODO.md`"                                    | same                                                                                         |
| `data-quality-audit` | "check the Bugs list in `TODO.md`" / "suggest a Bugs entry" | check `build/BACKLOG.md`, then file                                                           |
| `security-report`    | "check … the Bugs list in `TODO.md`"                        | check `build/BACKLOG.md` + `gh issue list --label area:security`                              |
| `codebase-audit`     | "Repairable defects are on the Bugs list in `TODO.md`"      | `build/BACKLOG.md`                                                                            |
| `scaffold-importer`  | accepted limitations go in KDoc, *not* TODO.md              | unchanged in substance; wording updated                                                      |

**d) `AGENTS.md`** — two references (§247 the bugs-list rule, §388 the importer-PR checklist) plus a new section documenting the tracker:
the label taxonomy, the milestone meanings, the snapshot read / `gh` write split, and the three new skills.

`docs/DATA_QUALITY_STRATEGY.md` deserves care — it says *"the authoritative task lives in `TODO.md` and this doc points at it — the two must not drift"*. That
contract survives the move intact; only the target changes.

---

## 11. New skills

Following the existing pattern: `.github/prompts/<name>.prompt.md` with a one-line `.claude/skills/<name>.md` pointing at it.

| Skill                  | Does                                                                                                                                                                                                                                                                                          |
|------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **`/new-issue`**       | Takes a description. **Searches for a duplicate first** (`build/BACKLOG.md` + `gh search issues`) and says so before creating anything. Picks the template, drafts the body in house style, sets type/labels/milestone/priority, adds to the project, and offers `related`/`blocked-by` links. |
| **`/next-issue`**      | Recommends what to work on next. Reads the *Ready to pick up* view, weighs priority, size, milestone urgency and unblocking value (an issue that unblocks three others outranks its own priority), and explains **why** — not just what.                                                      |
| **`/start-issue <n>`** | Assigns you, moves the project status to In progress, creates `<type>/<n>-<slug>`, reads the issue plus everything it references (files, ADRs, related issues), and produces a plan before touching code. Hands off to `/open-pr`, which gains `Closes #<n>` in the PR body.                  |

A fourth, **`/groom-backlog`**, is worth considering later but not now: it only pays off once the tracker has drifted, which it has not yet.

---

## 12. Sequence — six PRs

Deliberately not one PR. PR 3 creates 120 issues and is irreversible-ish; it should land on top of scaffolding that has already been reviewed.

| #     | PR                                                         | Contains                                                                                                                                                                                                             | Reviewable by                                                                           |
|-------|------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------|
| **1** ✅ | `chore(github): set up milestones, labels and the project` | 7 milestones · 16 new labels · project + Status/Priority fields + 4 views · 255 closed PRs → Phase 0 → closed · 5 new issue templates, chooser reordered, `type:` added to all 8                                    | reading the diff and one look at the project                                            |
| **2** ✅ | `docs(backlog): add the issue manifest`                    | `.github/backlog/` with **146** files · `scripts/backlog-sync.sh` · `README.md` · `validate-backlog.yml` CI job                                                                                                             | **the substantive review** — this is the backlog, rewritten, before anything is created |
| **3** ✅ | `chore(backlog): apply the manifest`                                   | `apply` → `link` → `project`. Issues **#258–#403**. Commits `.created.json` + one script fix.                                                                                                                                                | spot-check 10 issues                                                                    |
| **4** ✅ | *(folded into PR 3)*           | `link` and `project` produce no file changes beyond the lockfile, so splitting them bought nothing.                                                                                                                                   | the issue graph in the UI                                                               |
| **5** ✅ | `docs: retire TODO.md in favour of the issue tracker`             | **delete TODO.md** · the backlog snapshot · all 23 reference updates incl. the 5 Kotlin KDoc issue numbers · AGENTS.md tracker section · VISION_ROADMAP_IDEAS.md repointed at milestones | the big prose diff                                                                      |
| **6** ✅ | `feat(agents): add issue workflow skills`                  | `/new-issue`, `/next-issue`, `/start-issue` · `/open-pr` gains `Closes #N`. **The 5 prompt rewrites moved into PR 5** — deleting TODO.md while prompts still told agents to append to it would have left a broken window for a whole PR                                                                                             | a dry run of each                                                                       |

**Effort.** PR 2 is the bulk — ~120 issue bodies extracted from existing prose. It is mostly mechanical because the prose is already good, but it is a
multi-session job, not an afternoon. PRs 1, 3, 4 are fast. PR 5 is a careful afternoon. PR 6 is a session.

**Safe stopping points.** After PR 1 (nothing has changed except repo furniture) and after PR 4 (issues exist, TODO.md still exists — the only moment with two
sources of truth, so do not linger there).

---

## 13. Risks

| Risk                                                                                                                                                                                                                      | Mitigation                                                                                                                                                                   |
|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Agents lose the grep-able backlog** — the single biggest cost of this migration                                                                                                                                         | `build/BACKLOG.md`, generated on demand. Nothing else about the plan matters as much.                                                                                                   |
| **Cross-references dangle.** The Bugs list refers to itself constantly ("the same curated-vocabulary question as `NON_ARTIST_NAMES` below", "same place as the promoter fix above"). Split naively, those pointers break. | PR 4 is not polish, it is required. `validate` fails on a dangling key, so the manifest cannot ship half-linked.                                                             |
| **Two sources of truth during the migration**                                                                                                                                                                             | PRs 3–5 land close together. TODO.md is not edited after PR 2 is opened.                                                                                                     |
| **Prose gets flattened.** The value of TODO.md is that entries explain themselves; a terse issue title is a downgrade.                                                                                                    | The manifest is markdown files reviewed in a PR precisely so this is visible before it is permanent.                                                                         |
| **`release.yml` breaks** from a label rename                                                                                                                                                                              | No existing label is renamed or recoloured. Worth a release-notes preview after PR 1.                                                                                        |
| **120 open issues reads as a mountain**                                                                                                                                                                                   | Milestones + the `needs-deployment` label + the Blocked view. 19 of them are not work you are avoiding, they are work that cannot exist yet — and the tracker has to say so. |

---

## 14. What this also delivers

Two open TODO.md items close as a side effect:

- **"Create a public Roadmap (seed it from the phased roadmap)"** — public milestones plus a public project *are* the roadmap.
- **"Repository best-practices pass"** — substantially, via templates, labels, and a tracker that is no longer empty.

And one item gets easier: **"Venue or event missing? Let us know" form (→ GitHub issues?)** — the `new-venue` and `wrong-event-data`
templates already exist and can be deep-linked from the site once there is a tracker people would land in.

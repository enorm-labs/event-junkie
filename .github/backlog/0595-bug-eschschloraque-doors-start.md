---
slug: bug-eschschloraque-doors-start
title: Eschschloraque's doors/start split is published in prose and dropped
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:S", "needs-decision"]
priority: P2
status: Backlog
---

**What the source publishes.** The venue's date field carries one time. Where a night actually has
two, only the description says so:

- the Buletten Bingo openair writes `Einlass: 19:00` / `Beginn: 19:30` — and again as `Doors:` /
  `Starts:` in its English half
- the MissVergnügen anniversary writes `DJs ab 21 Uhr, Showtime ab 22 Uhr`

**What we store.** 19:00 as the start. So the stored time is really the **doors** time and the
actual 19:30 start is lost. **2 of 6 events at capture.**

**The fix.** Read the labelled pair out of the description and let `orderDoorsBeforeStart` place
them.

**The decision first.** May prose override the venue's own structured date field? That is a general
rule with consequences beyond this venue, and getting it wrong silently degrades sources that are
currently correct. The unlabelled `ab … Uhr` / `Showtime` phrasing also needs its own pattern —
labelled pairs are the easy half.

**Needs a `--full` re-seed?** One venue.

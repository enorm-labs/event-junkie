---
slug: bug-no-event-end-time
title: There is no event end time
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:M", "needs-decision"]
priority: P1
status: Backlog
related: [bug-late-night-drop]
---

**What the source publishes.** Kater publishes a full span — `Sa. 01.08 22:00 — So. 02.08 10:00`.
Heideglühen publishes a "bis Sonntag, 6 Uhr" tail.

**What we store.** Both as prose, because the model stores only a start.

**Why it matters beyond completeness.** This missing field is exactly why the late-night drop bug
needs a start-time heuristic instead of simply asking whether the event has ended. Fix this and that
one becomes a one-line comparison rather than a rule about clubs.

**The decision first.** An end time is optional for most sources and authoritative for a few, so the
display question comes with it: an event showing "22:00" and an event showing "22:00 – 10:00" look
like different kinds of data to a reader.

**Needs a `--full` re-seed?** No for the schema; yes to backfill the venues that publish a span.

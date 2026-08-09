---
slug: bug-no-event-room
title: There is no event-level room, so a multi-room venue loses which space a show plays in
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:M", "needs-decision"]
priority: P1
status: Backlog
---

**What happens.** `ScrapedArtist.stage` is the only home for a room, so the room survives **only
where there are acts to hang it on**.

**Blast radius, three shapes of the same gap:**

- **VOID Club** drops `VOID CLUB` / `VOID HALL` on its two `TBA` nights
- **Heimathafen** parses `(Saal)` / `(Studio)` out of its doors-time note and discards it
- **silent green** keeps `Kuppelhalle` / `Betonhalle` / `Atelier 2+3` only on its 33 concerts — the
  **54 exhibitions, talks and screenings** it publishes a hall for have no lineup to carry one

**The decision first.** An event-level field, plus a decision on how it relates to the per-artist
stage. They are not the same thing — a festival can put different acts in different rooms — so the
event-level field cannot simply replace `ScrapedArtist.stage`, and having both without a stated
relationship is how they drift.

**Needs a `--full` re-seed?** No — new field, populated going forward.

---
slug: bug-heimathafen-taxonomy
title: Heimathafen stores no genre only because the taxonomy is unresolved
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:M"]
priority: P1
status: Ready
---

**What the source publishes.** The venue *does* tag its events — but the REST payload carries term
**ids**, and the `class_list` slugs are lossy (`rb` for R&B).

**What we store.** No genre, for all 95 events. Not because the data is absent, but because the
lookup is missing.

**The fix.** Resolve the 560-term `events_tag` vocabulary once per import and cache it.

**Plus a stop-list**, because the vocabulary mixes real genres with formats and access notes —
`Konzert`, `Premiere`, `Gebärdensprache`. Importing it raw would fill the genre field with things
that are not genres, which is worse than the current emptiness: an empty field reads as missing, a
wrong one reads as data.

**Needs a `--full` re-seed?** One venue, so a targeted re-import.

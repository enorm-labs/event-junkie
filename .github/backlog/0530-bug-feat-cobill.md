---
slug: bug-feat-cobill
title: A `feat.` co-bill is stored as one artist
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:S"]
priority: P1
status: Ready
---

**What the source publishes.** Gärten der Welt's
`Stereoact: Ich liebe das Leben Party 2027 feat. Lena Marie Engel`.

**What we store.** A single 63-character "artist", instead of `Stereoact` plus a guest.

**Where.** `splitHeadlinerTitle` cuts a title on `/`, `+` and conjunctions. `ROLE_LABEL_PREFIX`
recognises `feat.` only where it *opens* a segment, never mid-title.

**The fix.** Split on the marker mid-title too, with the guest becoming `SUPPORT`. The marker is
already spelled out in `ROLE_LABEL_PREFIX` — this is about where it is allowed to match, not about
teaching the parser a new word.

**Needs a `--full` re-seed?** Yes — cross-cutting, so it needs a re-seed and a diff.

---
slug: bug-gartn-sup-cast-line
title: gART.n drops the guests named in a `<sup>` cast line
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:S"]
priority: P2
status: Ready
---

**What the source publishes.** A lineup line built only from a `<sup>`, used to name that slot's
cast:

```
Live Podcast "Heisse Platten"
mit Judith van Waterkant und Ruede Hagelstein
```

**What we store.** Nothing for the guests. `GartnOverviewPageScraper.billingLines` discards a line
built only from a `<sup>`, on the reasoning that such a line *annotates* the line above rather than
billing an act — which is right in general and wrong here.

**The fix.** Read a `mit …` / `w/ …` annotation as a cast line and split it via
`splitSegmentOnConjunctions`.

**The guardrail that matters.** The split needs the conjunction guardrails, since an act name may
legitimately contain `und`. Without them this trades one lost artist for several invented ones.

**Needs a `--full` re-seed?** One venue, so a targeted re-import is enough.

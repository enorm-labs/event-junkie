---
slug: bug-screening-german-compounds
title: The screening keyword misses German compounds
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:S"]
priority: P2
status: Ready
related: [bug-football-keywords]
---

**What happens.** `SCREENING_TITLE_WORD_PATTERN` (`EventTypeMapping.kt`) anchors on `\bkino\b` to
protect real act names — "Alkinoos Ioannidis" being the case it was written for.

**Result.** Kater's monthly `Nomadenkino` film night is typed `PARTY` instead of `SCREENING`.

**The fix.** A suffix-anchored match (`\w+kino\b`) that keeps the act-name guard. The guard is doing
real work and should not be removed; the anchor is simply in the wrong place for compounds, which
in German is most of them.

**Needs a `--full` re-seed?** Yes — cross-cutting type inference, so it needs a re-seed and a diff.

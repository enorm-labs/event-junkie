---
slug: find-missed-venues
title: Find venues we may have missed
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["importer", "documentation", "size:M"]
priority: P1
status: Ready
parent: more-importers-epic
---

Cross-check [theclubmap.com](https://www.theclubmap.com/music-style/), Resident Advisor and the
open web against [EVENT_DATA_SOURCES.md](../../docs/EVENT_DATA_SOURCES.md).

Worth doing **before** grinding through the Ready queue, not after: a venue that is missing from the
list is invisible to every prioritisation decision made from it, and the list is currently the only
input to "what next".

**Done when**

- [ ] The three sources cross-checked and the gaps added to EVENT_DATA_SOURCES.md
- [ ] Each new entry triaged into Ready / Blocked with a reason, like any other

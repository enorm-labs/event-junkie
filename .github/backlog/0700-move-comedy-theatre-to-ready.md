---
slug: move-comedy-theatre-to-ready
title: Move the comedy and theatre venues from Blocked to Ready
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["importer", "documentation", "size:S"]
priority: P1
status: Ready
---

**Coverage scope was decided on 2026-08-08: comedy clubs and theatres are in, sport is out.** Full
reasoning and cost in [EVENT_SCOPE.md §5](../../docs/EVENT_SCOPE.md), which is the record.

The venues sitting in [Blocked](../../docs/EVENT_DATA_SOURCES.md) purely on that scope question can
now move to Ready and be scaffolded like any other source, prioritised by programme richness as
usual.

**Two things about the decision that must not be re-litigated in an importer PR:**

- Sport's exclusion is **implemented**, in `AegOverviewPageScraper.isSport` and Velomax's type map.
  Reopening it means reopening that code, not just a doc (EVENT_SCOPE.md §3.1).
- **None of the scope decision may be reopened in an importer PR.**

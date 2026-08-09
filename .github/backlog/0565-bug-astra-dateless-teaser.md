---
slug: bug-astra-dateless-teaser
title: Astra's dateless featured teaser is dropped whenever its detail fetch fails
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "size:S"]
priority: P1
status: Ready
---

**What happens.** `11FREUNDE WM-QUARTIER` drops on **every run**.

The teaser carries no date of its own — the date lives on the detail page — so a single failed
fetch loses the event entirely rather than degrading to a partial record.

**The fix.** Either a retry, or reusing the last-known date for that `sourceId`. The second is
better: it survives a permanently flaky detail page rather than just an intermittent one.

**Fix it once for both venues.** Lido runs on the same Kulturhäuser platform and has the same
teaser, so the same failure is waiting there.

**Needs a `--full` re-seed?** No — the next successful import picks it up.

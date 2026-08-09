---
slug: admin-data-review-fixing
title: Data review and fixing — sort by missing fields, edit values
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "area:data-quality", "size:L"]
priority: P1
status: Backlog
parent: admin-frontend-epic
---

**As** a data steward
**I want** to sort and filter events by what is missing and fix them in place
**so that** a worklist becomes fixed data instead of a report.

Sort and filter events by missing fields; edit artist names, promoter names, event types and genres.

**The question this raises and must answer:** a hand-fixed value is overwritten by the next import
unless something protects it. Either edits are pinned per field, or fixes go into the correction
vocabulary rather than onto the row. That decision is closely tied to the curated-vocabulary
question — fixing a name once, in a map, is worth more than fixing it once, on a row, precisely
because the next import re-applies it.

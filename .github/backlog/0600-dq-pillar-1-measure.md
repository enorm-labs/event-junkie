---
slug: dq-pillar-1-measure
title: Pillar 1 (Measure) — a data-quality report endpoint, metrics and daily snapshots
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["area:data-quality", "area:bff", "size:L"]
priority: P0
status: Ready
related: [data-dashboard, admin-data-quality-overview]
---

**As** a data steward
**I want** per-source counts of what is missing or suspect
**so that** fixing data quality starts from a number instead of an impression.

The first pillar of [DATA_QUALITY_STRATEGY.md](../../docs/DATA_QUALITY_STRATEGY.md)
(Measure → Prevent → Fix → Systematize). Measuring first is the point of the ordering: without it,
every later pillar is judged on whether it *feels* like it helped.

**What it delivers**

- `GET /api/admin/data-quality` — per-source counts of artist-less concerts, `OTHER`-typed events,
  and missing genre / promoter / price / start-time
- a scheduled summary log and Micrometer gauges
- a `/worklist` endpoint returning the offending events per metric, so stewards fix through the
  existing Event API — **no bespoke frontend yet**
- daily metric snapshots in `data_quality_snapshot`, so trends are chartable in an external BI tool

The snapshot table is what makes the trend real rather than a live number that only ever describes
today. It is also what the admin data-quality overview and the analytics dashboard both read.

**References** — [DATA_QUALITY_PILLAR_1_PLAN.md](../../docs/DATA_QUALITY_PILLAR_1_PLAN.md)

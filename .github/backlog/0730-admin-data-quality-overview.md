---
slug: admin-data-quality-overview
title: Data-quality overview — per-metric, per-source, with a trend
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "area:data-quality", "size:M", "blocked"]
priority: P1
status: Blocked
parent: admin-frontend-epic
blocked-by: [dq-pillar-1-measure]
---

**As** a data steward
**I want** to see which sources have the worst data and whether it is getting better
**so that** effort goes where it changes the most rows.

Fed by the Pillar 1 endpoint and the `data_quality_snapshot` table — this is a view over data that
already exists by then, not a new measurement.

**Two questions to answer before designing the screen:**

1. **Which fields actually matter for the site?** Probably titles and everything used for filtering
   — genre, district, price, date. A missing field that nothing filters on is not a defect worth a
   red number.
2. **Which sources have the worst quality?** That ordering is the screen's whole job.

Getting (1) wrong produces a dashboard that is permanently red about things nobody cares about,
which is how a quality dashboard stops being read.

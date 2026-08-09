---
slug: data-dashboard
title: A dashboard for analysing the data
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["area:data-quality", "area:infra", "size:L"]
priority: P2
status: Backlog
related: [dq-pillar-1-measure, monitoring-alerting]
---

Superset, Kibana, Grafana or similar — for exploring the event data, and **also the intended surface
for the data-quality metrics and trends**.

Pillar 1 deliberately exposes its numbers in two forms so this can be an off-the-shelf tool rather
than a bespoke UI: a `data_quality_snapshot` table for SQL-based BI, and Micrometer/Prometheus
gauges for Grafana.

**Strong argument for Grafana specifically:** the monitoring work is already standing up
`kube-prometheus-stack` and Grafana. Reusing it means one observability stack instead of two, and
the data-quality trends land next to the operational ones — which is where someone asking "did that
importer change help?" would actually look.

**References** — [DATA_QUALITY_STRATEGY.md §4](../../docs/DATA_QUALITY_STRATEGY.md)

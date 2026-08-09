---
slug: monitoring-alerting
title: Monitoring and alerting, before launch rather than after the first outage
type: Task
milestone: v0.3 — Launch-ready
labels: ["area:infra", "size:L"]
priority: P0
status: Backlog
---

ADR-012 makes observability ours on Hetzner — there is no CloudWatch or Cloud Operations
equivalent. Two routes: `kube-prometheus-stack` with Grafana (the same surface the data dashboard
wants), or an external free tier.

**Alerting must exist before launch, not after the first outage.** Metrics without alerts are a
post-mortem tool; the point is to find out before someone else does.

**Done when**

- [ ] Metrics collected for the BFF, the importer and Postgres
- [ ] Alerts that actually page someone for: site down, importer failing repeatedly, database
      disk filling, certificate expiry
- [ ] A dashboard that answers "is it healthy" in one screen

Worth co-designing with the data-quality dashboard, which wants Grafana over the same Prometheus
data — building two observability stacks would be the avoidable mistake here.

---
slug: helm-chart
title: Write the Helm chart
type: Task
milestone: v0.2 — Deployable
labels: ["area:infra", "size:L", "blocked"]
priority: P0
status: Blocked
blocked-by: [accept-adr-012]
---

Three workloads behind one ingress:

- **`events-bff`** — N replicas
- **`events-importer`** — **`replicas: 1`, `strategy: Recreate`**, so a rolling deploy never runs
  two schedulers at once (ADR-008). This is not a tuning preference; two schedulers means two
  concurrent imports of the same source.
- **the frontend** — see the containerisation issue

The ingress routes `/` to the frontend and `/api` to the BFF, and **does not route the importer's
admin API publicly**. The importer exposes source management and on-demand import triggers; none of
that belongs on the public internet.

**Done when**

- [ ] All three workloads deploy from the chart
- [ ] The importer is pinned to one replica with `Recreate`
- [ ] The admin API is unreachable from outside the cluster
- [ ] The chart is exercised locally before it is pointed at anything real

**References** — `docs/adr/ADR-008_*`, `docs/adr/ADR-012_CLOUD_PLATFORM.md`

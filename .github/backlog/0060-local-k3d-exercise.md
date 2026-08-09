---
slug: local-k3d-exercise
title: Exercise the chart and images locally on k3d before deploying
type: Task
milestone: v0.2 — Deployable
labels: ["area:infra", "size:M", "blocked"]
priority: P1
status: Blocked
blocked-by: [helm-chart]
---

Run the Helm chart and the container images on a local Kubernetes (k3d or kind) before anything is
pointed at a real environment.

On the ADR-012 recommendation this local k3d work **is the production deployment path**, not a
rehearsal for a different target — the chart that runs here is the chart that runs on Hetzner k3s.
That makes it a genuine test rather than an approximation, which is not true of most local-cluster
setups.

**Done when**

- [ ] The full stack comes up on k3d from the chart
- [ ] Ingress routing (`/` → frontend, `/api` → BFF, admin API not exposed) is verified locally
- [ ] The importer runs a real import against a real Postgres in-cluster

Open question worth answering while here: whether LocalStack earns a place for any cloud services,
or whether the design avoids them entirely.

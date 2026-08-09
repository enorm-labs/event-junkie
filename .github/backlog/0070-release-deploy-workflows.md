---
slug: release-deploy-workflows
title: Create the release and deploy workflows (CI/CD)
type: Task
milestone: v0.2 — Deployable
labels: ["area:ci", "area:infra", "size:M", "blocked"]
priority: P0
status: Blocked
blocked-by: [helm-chart]
---

Build, tag and deploy from GitHub Actions.

**Known step down, recorded in ADR-012 rather than discovered later:** GitHub Actions cannot use
OIDC against Hetzner. Deploys therefore authenticate with a scoped kubeconfig or a deploy key held
as a repository secret. That is a long-lived credential where every other part of this stack avoids
one, so it needs **deliberate rotation** rather than a note in a runbook nobody reads.

**Done when**

- [ ] A tagged release builds and publishes images
- [ ] A deploy workflow rolls the chart out to an environment
- [ ] The deploy credential is scoped as narrowly as the platform allows, and its rotation is
      written down with an owner and an interval

**References** — `docs/adr/ADR-012_CLOUD_PLATFORM.md`

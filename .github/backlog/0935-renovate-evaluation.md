---
slug: renovate-evaluation
title: Evaluate an infra/tooling update checker beyond Dependabot
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:ci", "area:security", "size:S"]
priority: P2
status: Backlog
---

Dependabot covers Gradle and npm. It does not cover Docker base images, GitHub Actions pinned by
SHA, Helm chart dependencies or Terraform providers — which is most of what the `v0.2` work is about
to add.

Renovate is the obvious candidate. The question is whether to **replace** Dependabot or run both;
running both produces duplicate PRs for the overlapping ecosystems, which is worse than either alone.

Worth deciding once the infrastructure code exists, so the evaluation is against real manifests.

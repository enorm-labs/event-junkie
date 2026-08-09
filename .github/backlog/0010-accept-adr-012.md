---
slug: accept-adr-012
title: Settle the cloud platform — move ADR-012 from Proposed to Accepted
type: Task
milestone: v0.2 — Deployable
labels: ["area:infra", "needs-decision", "size:S"]
priority: P0
status: Ready
---

The evaluation is **complete**: 18 platforms across IaaS, CaaS and PaaS — including European PaaS,
AWS Elastic Beanstalk and App Engine — are costed and scored in
[ADR-012](../../docs/adr/ADR-012_CLOUD_PLATFORM.md). It recommends **Hetzner Cloud + k3s** at
roughly €30/month for production *and* staging, with a ranked fallback list: Sliplane/Coolify, then
Clever Cloud or Scalingo, then Cloud Run, then Beanstalk.

What is missing is not analysis. It is a decision. **Nothing else in this milestone can be built
until this one is made** — the Terraform provider, the Helm chart, the CI/CD authentication story
and the backup design all follow from it, and several of them invert entirely if a PaaS fallback is
taken instead.

**Done when**

- [ ] ADR-012's status is **Accepted**, naming the platform actually chosen
- [ ] If a fallback is taken rather than the recommendation, the ADR records why — the fallbacks
      change the frontend hosting decision and the CORS posture, not just the bill

**References** — `docs/adr/ADR-012_CLOUD_PLATFORM.md`

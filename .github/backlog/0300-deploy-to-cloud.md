---
slug: deploy-to-cloud
title: Deploy to production
type: Task
milestone: v1.0 — Go-live
labels: ["area:infra", "size:M", "blocked"]
priority: P0
status: Blocked
blocked-by: [release-deploy-workflows, staging-stage, postgres-backups-restore-drill, monitoring-alerting]
---

The first production deploy.

Deliberately sequenced after backups, monitoring and a working staging stage rather than before
them. A deploy is easy to do early and expensive to do early — an origin that exists without alerts
or a rehearsed restore is one that fails silently, and the failure is discovered by a visitor.

**Done when**

- [ ] Production runs the same chart that staging has been running
- [ ] The importer runs on schedule against the production database
- [ ] DNS points at it and TLS terminates correctly
- [ ] Alerts fire against production, verified by deliberately breaking something

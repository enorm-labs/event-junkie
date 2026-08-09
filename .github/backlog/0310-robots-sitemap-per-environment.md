---
slug: robots-sitemap-per-environment
title: Override robots.txt and sitemap.xml outside production
type: Task
milestone: v1.0 — Go-live
labels: ["area:seo", "area:infra", "size:S", "needs-deployment"]
priority: P0
status: Blocked
blocked-by: [staging-stage]
---

The build emits an allow-all `robots.txt` and a sitemap naming the **production** origin. Any
staging or preview environment serving that build therefore invites indexing — and points crawlers
at production while doing it.

This cannot be fixed in the frontend build: the build has one output and does not know which
environment will serve it. It is a deployment concern by design, so it has to be solved in the
deployment.

**Done when**

- [ ] Non-production environments serve a disallow-all `robots.txt`
- [ ] Non-production environments do not serve a sitemap naming production
- [ ] Verified by fetching both from staging, not by reading the config

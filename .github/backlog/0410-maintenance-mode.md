---
slug: maintenance-mode
title: Maintenance mode — a downtime page for deploys and outages
type: Feature
milestone: v1.0 — Go-live
labels: ["area:infra", "area:frontend", "size:M"]
priority: P2
status: Backlog
---

**As** a visitor arriving during a deploy or an outage
**I want** a page that says what is happening
**so that** I do not conclude the site is broken or gone.

Two halves: the frontend needs a page, and the BFF needs defined behaviour — a maintenance response
that the frontend can distinguish from a real error, and that does not look like a 500 to a crawler.

Worth deciding what a crawler should see, since an outage page returning 200 for every URL is a
good way to have the whole site deindexed.
